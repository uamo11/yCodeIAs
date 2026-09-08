package com.yugahashimoto.andcode.data.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionProfileTest {
    @Test
    fun `connection profiles round trip through json`() {
        val original =
            listOf(
                ConnectionProfile(
                    id = "mac-mini",
                    name = "Mac mini",
                    baseUrl = "http://192.168.1.10:4096/",
                    username = "opencode",
                    password = "secret",
                    allowInsecureLan = true,
                ),
            )

        val encoded = ConnectionProfileCodec.encode(original)
        val decoded = ConnectionProfileCodec.decode(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `toString never exposes password`() {
        val profile =
            ConnectionProfile(
                id = "pc",
                name = "PC",
                baseUrl = "https://example.com/",
                username = "opencode",
                password = "super-secret",
                allowInsecureLan = false,
            )

        assertFalse(profile.toString().contains("super-secret"))
    }

    @Test
    fun `ssh connection profile round trips and redacts secrets`() {
        val vps =
            ConnectionProfile(
                id = "vps-test",
                name = "My VPS",
                baseUrl = "http://127.0.0.1:4099",
                isSsh = true,
                sshHost = "198.51.100.1",
                sshPort = 2222,
                sshUser = "root",
                sshPassword = "vps-password",
                sshKey = "ssh-ed25519 AAAAC3...",
                remotePort = 4099,
            )

        val encoded = ConnectionProfileCodec.encode(listOf(vps))
        val decoded = ConnectionProfileCodec.decode(encoded).first()

        assertEquals(vps, decoded)
        assertFalse(vps.toString().contains("vps-password"))
        assertFalse(vps.toString().contains("AAAAC3"))
    }
}
