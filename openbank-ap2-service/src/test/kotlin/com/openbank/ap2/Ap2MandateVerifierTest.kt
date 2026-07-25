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
import com.openbank.ap2.infrastructure.observability.Ap2MetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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

    // The REAL metrics adapter over a SimpleMeterRegistry rather than a mock port, so the
    // instrumentation assertions below fail if the verifier stops emitting.
    private val registry = SimpleMeterRegistry()

    private fun verifier(trust: Map<String, String>) =
        Ap2MandateVerifier(JcaSignatureVerifier(), resolver(trust), Ap2MetricsAdapter(registry))

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

    @Test
    fun `a valid mandate is counted by kind, verdict and both stage outcomes, and timed`() {
        verifier(mapOf("issuer-1" to spkiB64)).verify(mandate(sign(signingInput)), goodPayment)

        assertThat(counter("openbank.ap2.mandate.verifications", "verdict", "valid")).isEqualTo(1.0)
        assertThat(counter("openbank.ap2.mandate.signature", "outcome", "valid")).isEqualTo(1.0)
        assertThat(counter("openbank.ap2.mandate.constraints", "outcome", "satisfied")).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.ap2.mandate.verification.duration")
                .tag("service", "ap2").tag("kind", "PAYMENT").timer().count(),
        ).isEqualTo(1L)
    }

    @Test
    fun `an untrusted issuer is counted as issuer_not_trusted, NOT as an invalid signature`() {
        // The distinction is the whole point: a rotated or mis-seeded trust list rejects a legitimate
        // issuer and looks identical, on the wire, to a forged one. Collapsing them would make a
        // configuration defect indistinguishable from an attack.
        verifier(emptyMap()).verify(mandate(sign(signingInput)), goodPayment)

        assertThat(counter("openbank.ap2.mandate.signature", "outcome", "issuer_not_trusted")).isEqualTo(1.0)
        assertThat(
            registry.find("openbank.ap2.mandate.signature").tag("outcome", "invalid").counters(),
        ).isEmpty()
        assertThat(counter("openbank.ap2.mandate.verifications", "verdict", "invalid")).isEqualTo(1.0)
    }

    @Test
    fun `a tampered signature is counted as invalid and a malformed one as verification_error`() {
        verifier(mapOf("issuer-1" to spkiB64)).verify(mandate(sign("a-different-payload")), goodPayment)
        verifier(mapOf("issuer-1" to spkiB64)).verify(mandate("!!!not-base64-sig!!!"), goodPayment)

        assertThat(counter("openbank.ap2.mandate.signature", "outcome", "invalid")).isEqualTo(1.0)
        assertThat(counter("openbank.ap2.mandate.signature", "outcome", "verification_error")).isEqualTo(1.0)
    }

    @Test
    fun `an out-of-constraint payment is counted as a violated CONSTRAINT with a valid signature`() {
        val overCap = PresentedPayment(payee, 200_00, "CZK", Instant.parse("2026-06-01T00:00:00Z"))

        verifier(mapOf("issuer-1" to spkiB64)).verify(mandate(sign(signingInput), cap = 100_00), overCap)

        assertThat(counter("openbank.ap2.mandate.signature", "outcome", "valid")).isEqualTo(1.0)
        assertThat(counter("openbank.ap2.mandate.constraints", "outcome", "violated")).isEqualTo(1.0)
        assertThat(counter("openbank.ap2.mandate.verifications", "verdict", "invalid")).isEqualTo(1.0)
    }

    @Test
    fun `no meter carries the attacker-controlled issuer or the mandate hash as a tag`() {
        // Cardinality + evidence contract: the issuer arrives in the request body on an agent-facing
        // surface, so tagging it would be an unbounded-series hole.
        verifier(mapOf("issuer-1" to spkiB64)).verify(mandate(sign(signingInput)), goodPayment)

        val tags = registry.meters.flatMap { it.id.tags }
        assertThat(tags.map { it.key }).doesNotContain("issuer", "subject", "mandate_hash", "payee")
        assertThat(tags.map { it.value }).doesNotContain("issuer-1", "cust-1", payee)
    }

    private fun counter(name: String, tagKey: String, tagValue: String): Double =
        registry.get(name).tag("service", "ap2").tag("kind", "PAYMENT").tag(tagKey, tagValue).counter().count()
}
