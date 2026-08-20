// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.openbank.audit.domain.crypto.AnchorSignatureVerifier
import com.openbank.audit.domain.model.AuditAnchor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Container-free unit tests for the ADR-0031 D5 anchor primitives: the canonical digest and the
 * asymmetric signature verification that replaced HMAC (issue #5838).
 *
 * The load-bearing property is **public-only verification**. It is proved structurally, not by
 * assertion: [signWithAThrowawayKey] is the only place a private key exists, it returns the
 * signature and the SPKI PEM and nothing else, and the key is unreachable from every test body
 * below. A test that merely declined to *use* a private key it still held would prove nothing —
 * that is exactly the property HMAC could not have and this one must.
 */
class AuditAnchorSigningTest {

    private val entryId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val headHash = "a".repeat(64)
    private val at = Instant.parse("2026-06-28T10:00:00Z")

    private fun digest(
        lastEntryId: UUID? = entryId,
        lastRecordHash: String? = headHash,
        count: Long = 42,
        status: String = "INTACT",
        signedAt: Instant = at,
    ) = AuditAnchor.digest(lastEntryId, lastRecordHash, count, status, signedAt)

    /**
     * Signs [payload] with a freshly generated P-256 key and returns `(signature, publicKeyPem)`.
     * The private key is local to this function and is never returned, stored, or otherwise
     * reachable — so anything a caller verifies, it verifies from public material alone.
     */
    private fun signWithAThrowawayKey(payload: ByteArray): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val signature = Signature.getInstance(AnchorSignatureVerifier.ALGORITHM).run {
            initSign(pair.private)
            update(payload)
            Base64.getEncoder().encodeToString(sign())
        }
        return signature to pem(pair.public.encoded)
    }

    private fun pem(spki: ByteArray): String = "-----BEGIN PUBLIC KEY-----\n" +
        Base64.getMimeEncoder(PEM_LINE_LENGTH, "\n".toByteArray()).encodeToString(spki) +
        "\n-----END PUBLIC KEY-----\n"

    @Test
    fun `digest is deterministic for identical inputs`() {
        assertThat(digest()).isEqualTo(digest())
    }

    @Test
    fun `digest is a 64-char lowercase hex sha-256`() {
        assertThat(digest()).matches("[0-9a-f]{64}")
    }

    @Test
    fun `any attested field changes the digest`() {
        val original = digest()
        assertThat(digest(lastRecordHash = "b".repeat(64))).isNotEqualTo(original)
        assertThat(digest(count = 43)).isNotEqualTo(original)
        assertThat(digest(status = "BROKEN")).isNotEqualTo(original)
        assertThat(digest(signedAt = at.plusSeconds(1))).isNotEqualTo(original)
        assertThat(digest(lastEntryId = UUID.randomUUID())).isNotEqualTo(original)
    }

    @Test
    fun `a valid anchor verifies from public key material alone`() {
        val payload = digest().toByteArray()
        val (signature, publicKeyPem) = signWithAThrowawayKey(payload)
        assertThat(AnchorSignatureVerifier.verify(payload, signature, publicKeyPem)).isTrue()
    }

    @Test
    fun `a tampered anchor fails verification (chain rewrite)`() {
        val (signature, publicKeyPem) = signWithAThrowawayKey(digest().toByteArray())
        val rewritten = digest(lastRecordHash = "b".repeat(64)).toByteArray()
        assertThat(AnchorSignatureVerifier.verify(rewritten, signature, publicKeyPem)).isFalse()
    }

    @Test
    fun `a tampered signature fails verification`() {
        val payload = digest().toByteArray()
        val (_, publicKeyPem) = signWithAThrowawayKey(payload)
        assertThat(AnchorSignatureVerifier.verify(payload, "not-a-valid-signature", publicKeyPem)).isFalse()
    }

    @Test
    fun `a signature made under a different key fails verification (forgery)`() {
        val payload = digest().toByteArray()
        val (foreignSignature, _) = signWithAThrowawayKey(payload)
        val (_, publicKeyPem) = signWithAThrowawayKey(payload)
        assertThat(AnchorSignatureVerifier.verify(payload, foreignSignature, publicKeyPem)).isFalse()
    }

    @Test
    fun `an invalid or empty public key fails verification rather than passing`() {
        val payload = digest().toByteArray()
        val (signature, _) = signWithAThrowawayKey(payload)
        assertThat(AnchorSignatureVerifier.verify(payload, signature, "")).isFalse()
        assertThat(AnchorSignatureVerifier.verify(payload, signature, "-----BEGIN PUBLIC KEY-----\nzzz\n")).isFalse()
        assertThat(AnchorSignatureVerifier.verify(payload, signature, "not a pem at all")).isFalse()
    }

    private companion object {
        const val PEM_LINE_LENGTH = 64
    }
}
