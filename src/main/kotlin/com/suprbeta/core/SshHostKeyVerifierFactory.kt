package com.suprbeta.core

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyFileUtil
import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.*
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import org.slf4j.Logger
import java.io.StringReader
import java.util.Base64

object SshHostKeyVerifierFactory {
    fun createProvisioningVerifier(application: Application): HostKeyVerifier {
        val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
        val provisionedHostPublicKey = dotenv["PROVISIONING_SSH_HOST_PUBLIC_KEY"] ?: System.getenv("PROVISIONING_SSH_HOST_PUBLIC_KEY")
        provisionedHostPublicKey?.trim().takeUnless { it.isNullOrBlank() }?.let { hostPublicKey ->
            logger(application).warn(
                "Provisioning SSH is accepting ephemeral host keys in test mode; " +
                    "host key mismatches against PROVISIONING_SSH_HOST_PUBLIC_KEY will be logged but not rejected"
            )
            return createProvisioningBootstrapVerifier(hostPublicKey, logger(application))
        }
        return createVerifier(
            description = "provisioning SSH",
            knownHostsContent = dotenv["PROVISIONING_SSH_KNOWN_HOSTS"] ?: System.getenv("PROVISIONING_SSH_KNOWN_HOSTS"),
            knownHostsBase64 = dotenv["PROVISIONING_SSH_KNOWN_HOSTS_B64"] ?: System.getenv("PROVISIONING_SSH_KNOWN_HOSTS_B64"),
            fingerprint = dotenv["PROVISIONING_SSH_HOST_FINGERPRINT"] ?: System.getenv("PROVISIONING_SSH_HOST_FINGERPRINT"),
            logger = logger(application)
        )
    }

    fun createSelfHostedVerifier(application: Application): HostKeyVerifier {
        val dotenv = dotenv { ignoreIfMissing = true; directory = "." }
        return createVerifier(
            description = "self-hosted Supabase SSH",
            knownHostsContent = dotenv["SUPABASE_SELF_HOSTED_SSH_KNOWN_HOSTS"] ?: System.getenv("SUPABASE_SELF_HOSTED_SSH_KNOWN_HOSTS"),
            knownHostsBase64 = dotenv["SUPABASE_SELF_HOSTED_SSH_KNOWN_HOSTS_B64"] ?: System.getenv("SUPABASE_SELF_HOSTED_SSH_KNOWN_HOSTS_B64"),
            fingerprint = dotenv["SUPABASE_SELF_HOSTED_SSH_HOST_FINGERPRINT"] ?: System.getenv("SUPABASE_SELF_HOSTED_SSH_HOST_FINGERPRINT"),
            logger = logger(application)
        )
    }

    internal fun createVerifier(
        description: String,
        knownHostsContent: String?,
        knownHostsBase64: String?,
        fingerprint: String?,
        logger: Logger
    ): HostKeyVerifier {
        val inlineKnownHosts = knownHostsContent?.trim().takeUnless { it.isNullOrBlank() }
        if (inlineKnownHosts != null) {
            logger.info("Using inline known_hosts content for $description")
            return OpenSSHKnownHosts(StringReader(normalizeMultiline(inlineKnownHosts)))
        }

        val encodedKnownHosts = knownHostsBase64?.trim().takeUnless { it.isNullOrBlank() }
        if (encodedKnownHosts != null) {
            val decoded = String(Base64.getDecoder().decode(normalizeBase64(encodedKnownHosts)))
            logger.info("Using base64-encoded known_hosts content for $description")
            return OpenSSHKnownHosts(StringReader(normalizeMultiline(decoded)))
        }

        val pinnedFingerprint = fingerprint?.trim().takeUnless { it.isNullOrBlank() }
        if (pinnedFingerprint != null) {
            logger.info("Using pinned host fingerprint for $description")
            return FingerprintVerifier.getInstance(pinnedFingerprint)
        }

        throw IllegalStateException(
            "Host key verification for $description requires known_hosts content or a pinned fingerprint. " +
                "Set one of: " +
                if (description == "provisioning SSH") {
                    "PROVISIONING_SSH_KNOWN_HOSTS, PROVISIONING_SSH_KNOWN_HOSTS_B64, PROVISIONING_SSH_HOST_FINGERPRINT"
                } else {
                    "SUPABASE_SELF_HOSTED_SSH_KNOWN_HOSTS, SUPABASE_SELF_HOSTED_SSH_KNOWN_HOSTS_B64, SUPABASE_SELF_HOSTED_SSH_HOST_FINGERPRINT"
                }
        )
    }

    private fun normalizeMultiline(value: String): String =
        value.trim().replace("\\n", "\n")

    private fun normalizeBase64(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        return normalized + "=".repeat(padding)
    }

    private fun createProvisioningBootstrapVerifier(publicKeyContent: String, logger: Logger): HostKeyVerifier {
        val parsed = OpenSSHKeyFileUtil.initPubKey(StringReader(normalizeMultiline(publicKeyContent)))
        val expectedFingerprint = SecurityUtils.getFingerprint(parsed.pubKey)
        return object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: java.security.PublicKey): Boolean {
                val actualFingerprint = SecurityUtils.getFingerprint(key)
                if (!actualFingerprint.equals(expectedFingerprint, ignoreCase = true)) {
                    logger.warn(
                        "Provisioning SSH host key mismatch for ${hostname ?: "unknown"}:$port " +
                            "(expected=$expectedFingerprint actual=$actualFingerprint); accepting for bootstrap"
                    )
                }
                return true
            }

            override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> = mutableListOf()
        }
    }

    private fun logger(application: Application): Logger = application.log
}
