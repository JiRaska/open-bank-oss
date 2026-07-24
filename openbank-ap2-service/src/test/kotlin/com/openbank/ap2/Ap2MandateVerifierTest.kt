// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.ap2

import com.openbank.ap2.application.Ap2MandateVerifier
import com.openbank.ap2.application.port.out.MandateKeyResolver
import com.openbank.ap2.domain.Ap2Mandate
import com.openbank.ap2.domain.MandateConstraints
import com.openbank.ap2.domain.MandateKind
import com.openbank.ap2.domain.MandateSignatureAlgorithm
import com.openbank.ap2.domain.PresentedPayment
import com.openbank.ap2.infrastructure.crypto.JcaSignatureVerifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64

/**
 * End-to-end verifier with REAL Ed25519 crypto (JcaSignatureVerifier, the shipped impl): a genuinely
 * signed mandate verifies; a tampered signature, an untrusted issuer, and a constraint violation each
 * fail closed. Proves the two-stage model of ADR-0193 §1.
 */
class Ap2MandateVerifierTest {

    private val payee = "CZ6508000000192000145399"
    private val expiry = Instant.parse("2026-12-31T00:00:00Z")
    private val signingInput = "eyJhbGciOiJFZERTQSJ9.eyJraW5kIjoiUEFZTUVOVCJ9"

    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val spkiB64: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun sign(input: String): String {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(keyPair.private)
        sig.update(input.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun resolver(map: Map<String, String>) = object : MandateKeyResolver {
        override fun resolve(issuer: String): String? = map[issuer]
    }

    private fun verifier(trust: Map<String, String>) = Ap2MandateVerifier(JcaSignatureVerifier(), resolver(trust))

    private fun mandate(sig: String, cap: Long = 100_00) = Ap2Mandate(
        kind = MandateKind.PAYMENT,
        issuer = "issuer-1",
        subject = "cust-1",
        constraints = MandateConstraints(payee, cap, "CZK", expiry),
        signingInput = signingInput,
        signatureB64 = sig,
        algorithm = MandateSignatureAlgorithm.ED25519,
    )

    private val goodPayment = PresentedPayment(payee, 50_00, "CZK", Instant.parse("2026-06-01T00:00:00Z"))

    @Test
    fun `genuinely signed, in-bounds mandate is valid`() {
        val v = verifier(mapOf("issuer-1" to spkiB64)).verify(mandate(sign(signingInput)), goodPayment)
        assertThat(v.valid).isTrue()
        assertThat(v.evidence.signatureValid).isTrue()
        assertThat(v.evidence.constraintsSatisfied).isTrue()
        assertThat(v.evidence.mandateHash).isNotBlank()
        assertThat(v.failures).isEmpty()
    }

    @Test
    fun `tampered signature is invalid`() {
        val otherSig = sign("a-different-payload")
        val v = verifier(mapOf("issuer-1" to spkiB64)).verify(mandate(otherSig), goodPayment)
        assertThat(v.valid).isFalse()
        assertThat(v.evidence.signatureValid).isFalse()
        assertThat(v.failures).anyMatch { it.contains("signature") }
    }

    @Test
    fun `untrusted issuer is invalid and never checks a key`() {
        val v = verifier(emptyMap()).verify(mandate(sign(signingInput)), goodPayment)
        assertThat(v.valid).isFalse()
        assertThat(v.failures).anyMatch { it.contains("not trusted") }
    }

    @Test
    fun `valid signature but out-of-constraint payment is invalid`() {
        val overCap = PresentedPayment(payee, 200_00, "CZK", Instant.parse("2026-06-01T00:00:00Z"))
        val v = verifier(mapOf("issuer-1" to spkiB64)).verify(mandate(sign(signingInput), cap = 100_00), overCap)
        assertThat(v.valid).isFalse()
        assertThat(v.evidence.signatureValid).isTrue()
        assertThat(v.evidence.constraintsSatisfied).isFalse()
        assertThat(v.failures).anyMatch { it.contains("exceeds cap") }
    }

    @Test
    fun `malformed signature bytes fail closed, never throw`() {
        val v = verifier(mapOf("issuer-1" to spkiB64)).verify(mandate("!!!not-base64-sig!!!"), goodPayment)
        assertThat(v.valid).isFalse()
        assertThat(v.evidence.signatureValid).isFalse()
    }
}
