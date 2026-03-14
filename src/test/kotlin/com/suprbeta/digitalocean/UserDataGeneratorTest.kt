package com.suprbeta.digitalocean

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserDataGeneratorTest {
    private val hostKeyMaterial = UserDataGenerator.ProvisioningHostKeyMaterial(
        privateKeyPem = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            test-private-key
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent(),
        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestHostKey suprclaw-host"
    )

    @Test
    fun `bootstrap user data includes pinned host key material`() {
        val userData = UserDataGenerator.generateBootstrapUserData(
            password = "secret-password",
            hostKeyMaterial = hostKeyMaterial
        )

        assertTrue(userData.contains("/etc/ssh/ssh_host_ed25519_key"))
        assertTrue(userData.contains("test-private-key"))
        assertTrue(userData.contains(hostKeyMaterial.publicKey))
        assertFalse(userData.contains("{{HOST_SSH_PRIVATE_KEY}}"))
        assertFalse(userData.contains("{{HOST_SSH_PUBLIC_KEY}}"))
    }

    @Test
    fun `podman host user data includes pinned host key material`() {
        val userData = UserDataGenerator.generatePodmanHostUserData(
            sshPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestUserKey picoclaw-user",
            hostKeyMaterial = hostKeyMaterial
        )

        assertTrue(userData.contains("/etc/ssh/ssh_host_ed25519_key"))
        assertTrue(userData.contains("test-private-key"))
        assertTrue(userData.contains(hostKeyMaterial.publicKey))
        assertFalse(userData.contains("{{HOST_SSH_PRIVATE_KEY}}"))
        assertFalse(userData.contains("{{HOST_SSH_PUBLIC_KEY}}"))
    }
}
