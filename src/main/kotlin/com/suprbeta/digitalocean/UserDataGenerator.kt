package com.suprbeta.digitalocean

import java.security.SecureRandom

object UserDataGenerator {

    private const val PASSWORD_LENGTH = 24
    private val PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%^&*"

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
     * Generates bootstrap cloud-config user-data that ONLY creates the openclaw user
     * with the given password. No onboarding happens here — that's done via SSH later.
     */
    fun generateBootstrapUserData(password: String): String {
        val template = this::class.java.classLoader
            .getResourceAsStream("scripts/user.yaml")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("user.yaml not found in resources")

        return template.replace("{{PASSWORD}}", password)
    }

    /**
     * Generates cloud-init user-data for a Docker host VPS.
     * This installs Docker, Traefik, creates the openclaw user with the provisioning SSH key,
     * and writes a sentinel file /var/run/suprclaw-host-ready when setup is complete.
     */
    fun generateDockerHostUserData(sshPublicKey: String): String {
        val template = this::class.java.classLoader
            .getResourceAsStream("scripts/docker-host.yaml")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("docker-host.yaml not found in resources")

        return template.replace("{{SSH_PUBLIC_KEY}}", sshPublicKey)
    }
}
