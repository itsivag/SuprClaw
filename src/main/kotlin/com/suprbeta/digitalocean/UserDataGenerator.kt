package com.suprbeta.digitalocean

import io.github.cdimascio.dotenv.dotenv
import java.security.SecureRandom
import java.util.Base64

object UserDataGenerator {
    data class ProvisioningHostKeyMaterial(
        val privateKeyPem: String,
        val publicKey: String
    )

    private const val PASSWORD_LENGTH = 24
    private val PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%^&*"
    private val dotenv = dotenv { ignoreIfMissing = true; directory = "." }

    /**
     * Generates a cryptographically secure random password.
     */
    fun generatePassword(): String {
        val random = SecureRandom()
        return (1..PASSWORD_LENGTH)
            .map { PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)] }
            .joinToString("")
    }

    /**
     * Generates bootstrap cloud-config user-data that ONLY creates the runtime user
     * with the given password. No onboarding happens here — that's done via SSH later.
     */
    fun generateBootstrapUserData(
        password: String,
        hostKeyMaterial: ProvisioningHostKeyMaterial = loadProvisioningHostKeyMaterial()
    ): String {
        val template = this::class.java.classLoader
            .getResourceAsStream("scripts/user.yaml")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("user.yaml not found in resources")

        return template
            .replace("{{PASSWORD}}", password)
            .replace("{{HOST_SSH_PRIVATE_KEY}}", indentBlock(hostKeyMaterial.privateKeyPem))
            .replace("{{HOST_SSH_PUBLIC_KEY}}", indentBlock(hostKeyMaterial.publicKey))
    }

    /**
     * Generates cloud-init user-data for a Docker host VPS.
     * This installs Docker, Traefik, creates the runtime user with the provisioning SSH key,
     * and writes a sentinel file /var/run/suprclaw-host-ready when setup is complete.
     */
    fun generateDockerHostUserData(
        sshPublicKey: String,
        hostKeyMaterial: ProvisioningHostKeyMaterial = loadProvisioningHostKeyMaterial()
    ): String {
        val template = this::class.java.classLoader
            .getResourceAsStream("scripts/docker-host.yaml")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("docker-host.yaml not found in resources")

        return template
            .replace("{{SSH_PUBLIC_KEY}}", sshPublicKey)
            .replace("{{HOST_SSH_PRIVATE_KEY}}", indentBlock(hostKeyMaterial.privateKeyPem))
            .replace("{{HOST_SSH_PUBLIC_KEY}}", indentBlock(hostKeyMaterial.publicKey))
    }

    internal fun loadProvisioningHostKeyMaterial(
        privateKeyBase64: String? = env("PROVISIONING_SSH_HOST_PRIVATE_KEY_B64"),
        publicKey: String? = env("PROVISIONING_SSH_HOST_PUBLIC_KEY")
    ): ProvisioningHostKeyMaterial {
        val encodedPrivateKey = privateKeyBase64?.trim().takeUnless { it.isNullOrBlank() }
            ?: throw IllegalStateException("PROVISIONING_SSH_HOST_PRIVATE_KEY_B64 not found in environment")
        val trimmedPublicKey = publicKey?.trim().takeUnless { it.isNullOrBlank() }
            ?: throw IllegalStateException("PROVISIONING_SSH_HOST_PUBLIC_KEY not found in environment")

        val privateKeyPem = String(Base64.getDecoder().decode(normalizeBase64(encodedPrivateKey))).trimEnd()
        return ProvisioningHostKeyMaterial(
            privateKeyPem = privateKeyPem,
            publicKey = trimmedPublicKey
        )
    }

    private fun indentBlock(content: String, spaces: Int = 6): String {
        val prefix = " ".repeat(spaces)
        return content.trimEnd().lines().joinToString("\n") { "$prefix$it" }
    }

    private fun normalizeBase64(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        return normalized + "=".repeat(padding)
    }

    private fun env(key: String): String? =
        dotenv[key] ?: System.getenv(key)
}
