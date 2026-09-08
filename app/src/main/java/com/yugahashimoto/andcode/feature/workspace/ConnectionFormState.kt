package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.security.OpenCodeUrl
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import java.util.UUID

enum class ConnectionMode {
    VPS_SSH,
    DIRECT_HTTP,
}

data class ConnectionFormState(
    val id: String = UUID.randomUUID().toString(),
    val mode: ConnectionMode = ConnectionMode.VPS_SSH,
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "opencode",
    val password: String = "",
    val allowInsecureLan: Boolean = false,
    val isTesting: Boolean = false,
    val testMessage: String? = null,
    val testSucceeded: Boolean = false,
    val sshHost: String = "",
    val sshPort: String = "22",
    val sshUser: String = "root",
    val sshPassword: String = "",
    val sshKey: String = "",
    val remotePort: String = "4099",
    val isBootstrapping: Boolean = false,
    val bootstrapProgress: String? = null,
    val bootstrapLogs: List<String> = emptyList(),
) {
    private val parsedUrl
        get() = OpenCodeUrl.normalize(baseUrl).getOrNull()

    val normalizedUrl: String?
        get() = parsedUrl?.toString()

    val canSave: Boolean
        get() =
            if (mode == ConnectionMode.VPS_SSH && sshHost.isNotBlank()) {
                name.isNotBlank()
            } else {
                name.isNotBlank() && parsedUrl != null
            }

    fun toProfile(): ConnectionProfile {
        return if (mode == ConnectionMode.VPS_SSH && sshHost.isNotBlank()) {
            val rPort = remotePort.toIntOrNull()?.takeIf { it > 0 } ?: 4099
            ConnectionProfile(
                id = id,
                name = name.trim().ifBlank { sshHost.trim() },
                baseUrl = "http://127.0.0.1:$rPort",
                username = "opencode",
                password = password.takeIf { it.isNotBlank() },
                allowInsecureLan = true,
                isSsh = true,
                sshHost = sshHost.trim(),
                sshPort = sshPort.toIntOrNull()?.takeIf { it > 0 } ?: 22,
                sshUser = sshUser.trim().ifBlank { "root" },
                sshPassword = sshPassword.takeIf { it.isNotBlank() },
                sshKey = sshKey.takeIf { it.isNotBlank() },
                remotePort = rPort,
            )
        } else {
            val url = requireNotNull(parsedUrl) { "Endpoint is not a valid OpenCode URL" }
            ConnectionProfile(
                id = id,
                name = name.trim(),
                baseUrl = url.toString(),
                username = username.trim().ifBlank { "opencode" },
                password = password.takeIf { it.isNotBlank() },
                allowInsecureLan = allowInsecureLan || url.scheme == "http",
                isSsh = false,
            )
        }
    }

    companion object {
        fun from(profile: ConnectionProfile): ConnectionFormState =
            ConnectionFormState(
                id = profile.id,
                mode = if (profile.isSsh) ConnectionMode.VPS_SSH else ConnectionMode.DIRECT_HTTP,
                name = profile.name,
                baseUrl = profile.baseUrl,
                username = profile.username,
                password = profile.password.orEmpty(),
                allowInsecureLan = profile.allowInsecureLan,
                sshHost = profile.sshHost.orEmpty(),
                sshPort = profile.sshPort.toString(),
                sshUser = profile.sshUser,
                sshPassword = profile.sshPassword.orEmpty(),
                sshKey = profile.sshKey.orEmpty(),
                remotePort = profile.remotePort.toString(),
                testSucceeded = true,
            )
    }
}
