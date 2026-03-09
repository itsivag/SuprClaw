package com.suprbeta.core

import net.schmizz.sshj.transport.verification.FingerprintVerifier
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SshHostKeyVerifierFactoryTest {
    private val logger = LoggerFactory.getLogger(SshHostKeyVerifierFactoryTest::class.java)

    @Test
    fun `createVerifier accepts pinned fingerprints`() {
        val verifier = SshHostKeyVerifierFactory.createVerifier(
            description = "test SSH",
            knownHostsContent = null,
            knownHostsBase64 = null,
            fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            logger = logger
        )

        assertTrue(verifier is FingerprintVerifier)
    }

    @Test
    fun `createVerifier rejects missing trust material`() {
        val exception = assertFailsWith<IllegalStateException> {
            SshHostKeyVerifierFactory.createVerifier(
                description = "test SSH",
                knownHostsContent = null,
                knownHostsBase64 = null,
                fingerprint = null,
                logger = logger
            )
        }

        assertTrue(exception.message!!.contains("Host key verification"))
    }
}
