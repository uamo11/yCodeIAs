package com.yugahashimoto.andcode.data.connection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ConnectionProfile(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("name") val name: String,
    @SerialName("baseUrl") val baseUrl: String,
    @SerialName("username") val username: String = "opencode",
    @SerialName("password") val password: String? = null,
    @SerialName("allowInsecureLan") val allowInsecureLan: Boolean = false,
    @SerialName("pinSha256") val pinSha256: String? = null,
    @SerialName("isSsh") val isSsh: Boolean = false,
    @SerialName("sshHost") val sshHost: String? = null,
    @SerialName("sshPort") val sshPort: Int = 22,
    @SerialName("sshUser") val sshUser: String = "root",
    @SerialName("sshPassword") val sshPassword: String? = null,
    @SerialName("sshKey") val sshKey: String? = null,
    @SerialName("remotePort") val remotePort: Int = 4099,
) {
    override fun toString(): String =
        "ConnectionProfile(id=$id, name=$name, baseUrl=$baseUrl, isSsh=$isSsh, sshHost=$sshHost, sshPort=$sshPort, sshUser=$sshUser, remotePort=$remotePort, password=<redacted>, allowInsecureLan=$allowInsecureLan, pinSha256=<redacted>)"
}

object ConnectionProfileCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    fun encode(profiles: List<ConnectionProfile>): String = json.encodeToString(profiles)

    fun decode(jsonString: String): List<ConnectionProfile> {
        if (jsonString.isBlank()) return emptyList()
        return json.decodeFromString<List<ConnectionProfile>>(jsonString)
    }
}
