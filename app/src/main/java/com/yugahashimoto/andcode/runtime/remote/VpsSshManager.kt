package com.yugahashimoto.andcode.runtime.remote

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

data class VpsBootstrapResult(
    val status: String,
    val port: Int,
    val version: String,
    val distro: String,
    val arch: String,
    val hasSystemd: Boolean,
    val binary: String,
)

class VpsSshManager {
    private val jsch = JSch()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val localPorts = ConcurrentHashMap<String, Int>()
    private val mutex = Mutex()

    suspend fun testConnection(profile: ConnectionProfile): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = createSession(profile)
                try {
                    session.connect(15_000)
                    val channel = session.openChannel("exec") as ChannelExec
                    channel.setCommand("uname -a")
                    val input = channel.inputStream
                    channel.connect(10_000)
                    val output = input.bufferedReader().readText().trim()
                    channel.disconnect()
                    output
                } finally {
                    session.disconnect()
                }
            }
        }

    suspend fun setupVps(
        profile: ConnectionProfile,
        bootstrapScript: String,
        onProgress: (String) -> Unit,
    ): Result<VpsBootstrapResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val host = profile.sshHost ?: error("Host SSH no configurado")
                onProgress("🔌 Conectando por SSH a $host:${profile.sshPort}...")
                val session = createSession(profile)
                session.connect(15_000)

                try {
                    onProgress("🚀 Ejecutando script de instalación y detección universal...")
                    val channel = session.openChannel("exec") as ChannelExec
                    // Pass script via stdin to avoid filesystem permissions issues
                    val port = profile.remotePort.takeIf { it > 0 } ?: 4099
                    channel.setCommand("bash -s -- $port setup")
                    channel.setInputStream(ByteArrayInputStream(bootstrapScript.toByteArray(Charsets.UTF_8)))
                    
                    val stdoutStream = channel.inputStream
                    val stderrStream = channel.errStream
                    channel.connect(15_000)

                    val reader = BufferedReader(InputStreamReader(stdoutStream, Charsets.UTF_8))
                    val fullOutput = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: break
                        fullOutput.appendLine(currentLine)
                        if (currentLine.startsWith("[ycode-vps]")) {
                            onProgress(currentLine.removePrefix("[ycode-vps]").trim())
                        }
                    }

                    val exitStatus = channel.exitStatus
                    channel.disconnect()

                    val resultText = fullOutput.toString()
                    val jsonStart = resultText.indexOf("__YCODE_RESULT_START__")
                    val jsonEnd = resultText.indexOf("__YCODE_RESULT_END__")

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val jsonStr = resultText.substring(jsonStart + "__YCODE_RESULT_START__".length, jsonEnd).trim()
                        val json = JSONObject(jsonStr)
                        if (json.optString("status") == "ok") {
                            val result = VpsBootstrapResult(
                                status = "ok",
                                port = json.optInt("port", port),
                                version = json.optString("version", "unknown"),
                                distro = json.optString("distro", "Linux"),
                                arch = json.optString("arch", "unknown"),
                                hasSystemd = json.optBoolean("has_systemd", false),
                                binary = json.optString("binary", ""),
                            )
                            onProgress("✅ ¡OpenCode v${result.version} listo en ${result.distro} (${result.arch})!")
                            return@runCatching result
                        } else {
                            val msg = json.optString("message", "Error desconocido durante la instalación")
                            error(msg)
                        }
                    }

                    if (exitStatus != 0) {
                        error("El instalador terminó con código $exitStatus")
                    }

                    VpsBootstrapResult(
                        status = "ok",
                        port = port,
                        version = "unknown",
                        distro = "Linux",
                        arch = "unknown",
                        hasSystemd = false,
                        binary = "opencode",
                    )
                } finally {
                    session.disconnect()
                }
            }
        }

    suspend fun ensureTunnel(profile: ConnectionProfile): Int =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val profileId = profile.id
                val existingSession = sessions[profileId]
                val existingPort = localPorts[profileId]

                if (existingSession != null && existingSession.isConnected && existingPort != null) {
                    return@withContext existingPort
                }

                // Disconnect stale session if any
                disconnect(profileId)

                val session = createSession(profile)
                session.connect(15_000)

                val freePort = ServerSocket(0).use { it.localPort }
                val remotePort = profile.remotePort.takeIf { it > 0 } ?: 4099
                session.setPortForwardingL(freePort, "127.0.0.1", remotePort)

                sessions[profileId] = session
                localPorts[profileId] = freePort
                Log.d("VpsSshManager", "SSH Tunnel established: 127.0.0.1:$freePort -> ${profile.sshHost}:$remotePort")
                freePort
            }
        }

    fun getActiveLocalPort(profileId: String): Int? {
        val session = sessions[profileId]
        if (session != null && session.isConnected) {
            return localPorts[profileId]
        }
        return null
    }

    fun disconnect(profileId: String) {
        try {
            val session = sessions.remove(profileId)
            localPorts.remove(profileId)
            session?.disconnect()
        } catch (e: Exception) {
            Log.w("VpsSshManager", "Error disconnecting session for $profileId", e)
        }
    }

    fun disconnectAll() {
        for ((id, _) in sessions) {
            disconnect(id)
        }
    }

    private fun createSession(profile: ConnectionProfile): Session {
        val host = profile.sshHost ?: error("Host SSH no configurado")
        val user = profile.sshUser.takeIf { it.isNotBlank() } ?: "root"
        val port = profile.sshPort.takeIf { it > 0 } ?: 22

        profile.sshKey?.takeIf { it.isNotBlank() }?.let { keyContent ->
            jsch.addIdentity("vps-key-${profile.id}", keyContent.toByteArray(Charsets.UTF_8), null, null)
        }

        val session = jsch.getSession(user, host, port)
        profile.sshPassword?.takeIf { it.isNotBlank() }?.let { pwd ->
            session.setPassword(pwd)
        }

        val config = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("UserKnownHostsFile", "/dev/null")
        }
        session.setConfig(config)
        session.setServerAliveInterval(15_000)
        session.setServerAliveCountMax(3)
        return session
    }
}
