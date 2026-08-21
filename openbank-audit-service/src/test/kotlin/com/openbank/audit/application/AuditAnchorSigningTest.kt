// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.openbank.audit.domain.model.AuditAnchor
import com.openbank.audit.infrastructure.signing.LocalHmacAnchorSigner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Container-free unit tests for the ADR-0031 D5 anchor primitives: the canonical digest and the
 * default HMAC signer. These cover the tamper-evidence guarantees that make a signed anchor
 * stronger than the internal-consistency hash-chain walk.
 */
class AuditAnchorSigningTest {

    private val entryId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val headHash = "a".repeat(64)
    private val at = Instant.parse("2026-06-28T10:00:00Z")
    private val signer = LocalHmacAnchorSigner("unit-test-key")

    private fun digest(
        lastEntryId: UUID? = entryId,
        lastRecordHash: String? = headHash,
        count: Long = 42,
        status: String = "INTACT",
        signedAt: Instant = at,
    ) = AuditAnchor.digest(lastEntryId, lastRecordHash, count, status, signedAt)

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
    fun `signer round-trips a valid signature`() {
        val d = digest().toByteArray()
        val signed = signer.sign(d)
        assertThat(signer.verify(d, signed.value, signed.keyId)).isTrue()
    }

    @Test
    fun `signer rejects a signature over a different digest (chain rewrite)`() {
        val signed = signer.sign(digest().toByteArray())
        val rewritten = digest(lastRecordHash = "b".repeat(64)).toByteArray()
        assertThat(signer.verify(rewritten, signed.value, signed.keyId)).isFalse()
    }

    @Test
    fun `signer rejects a tampered signature`() {
        val d = digest().toByteArray()
        assertThat(signer.verify(d, "not-a-valid-signature", signer.keyId)).isFalse()
    }

    @Test
    fun `a different key produces a different signature`() {
        val d = digest().toByteArray()
        assertThat(LocalHmacAnchorSigner("other-key").sign(d)).isNotEqualTo(signer.sign(d))
    }
}
