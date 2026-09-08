package com.yugahashimoto.andcode.feature.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFormStateTest {
    @Test
    fun `save is disabled when name or endpoint is missing`() {
        assertFalse(ConnectionFormState().canSave)
        assertFalse(ConnectionFormState(name = "Mac mini").canSave)
        assertFalse(ConnectionFormState(baseUrl = "192.168.1.10:4096").canSave)
    }

    @Test
    fun `save is enabled for valid LAN and HTTPS endpoints`() {
        assertTrue(
            ConnectionFormState(
                name = "Mac mini",
                baseUrl = "192.168.1.10:4096",
                allowInsecureLan = true,
            ).canSave,
        )
        assertTrue(
            ConnectionFormState(
                name = "Server",
                baseUrl = "https://opencode.example.com",
            ).canSave,
        )
    }

    @Test
    fun `plain LAN endpoint is saveable without a separate cleartext opt-in`() {
        val form = ConnectionFormState(name = "Mac mini", baseUrl = "192.168.1.10:4096")

        assertTrue(form.canSave)
        val profile = form.toProfile()
        assertEquals("http://192.168.1.10:4096/", profile.baseUrl)
        assertTrue(profile.allowInsecureLan)
    }

    @Test
    fun `https endpoint is not marked as allowing cleartext`() {
        val profile =
            ConnectionFormState(
                name = "Server",
                baseUrl = "https://opencode.example.com",
            ).toProfile()

        assertFalse(profile.allowInsecureLan)
    }

    @Test
    fun `public cleartext endpoint cannot be saved`() {
        assertFalse(
            ConnectionFormState(
                name = "Unsafe",
                baseUrl = "http://example.com:4096",
                allowInsecureLan = true,
            ).canSave,
        )
    }

    @Test
    fun `vps ssh form can save when name and host are provided`() {
        val form =
            ConnectionFormState(
                mode = ConnectionMode.VPS_SSH,
                name = "Test VPS",
                sshHost = "198.51.100.1",
                sshPort = "2222",
                sshUser = "root",
                sshPassword = "password123",
                remotePort = "4099",
            )

        assertTrue(form.canSave)
        val profile = form.toProfile()
        assertTrue(profile.isSsh)
        assertEquals("Test VPS", profile.name)
        assertEquals("198.51.100.1", profile.sshHost)
        assertEquals(2222, profile.sshPort)
        assertEquals("root", profile.sshUser)
        assertEquals("password123", profile.sshPassword)
        assertEquals(4099, profile.remotePort)
        assertEquals("http://127.0.0.1:4099", profile.baseUrl)
    }
}
