// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.persistence

import com.openbank.audit.domain.model.AttributionSource
import com.openbank.audit.domain.model.AuditAnchor
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Pins the two canonical forms the OFFLINE verifier re-implements (issue #5838).
 *
 * `.github/scripts/verify-audit-anchors.py` deliberately does not import a line of this service:
 * a third party must be able to check the anchors without running the bank's code, and a verifier
 * that shared the producer's implementation could not detect a producer that changed it. The cost
 * of that independence is that the two can drift apart silently — and the failure is invisible
 * from either side alone, because each is self-consistent. A round-trip against your own encoder
 * cannot see a spec divergence.
 *
 * So the literals below are the contract, asserted from BOTH sides: the Python self-test asserts
 * it produces exactly these, and this test asserts the real Kotlin does too. Change either
 * canonical form and exactly one side goes red, which is the whole point — the anchors already
 * signed were signed under the old form, and silently re-defining it retroactively invalidates
 * every historical attestation.
 *
 * The vectors deliberately include the awkward cases: a MICROSECOND timestamp (six fraction
 * digits) beside a WHOLE-SECOND one (no fraction at all), because `Instant.toString()` prints
 * fractions in groups of 3/6/9 and omits them when zero, which no naive ISO-8601 formatter
 * reproduces; and an anchor over a null head, which is what an empty chain attests.
 */
class OfflineVerifierConformanceTest {

    private val entry = AuditEntry(
        id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        eventType = "payment.initiated",
        aggregateType = "Payment",
        aggregateId = "PMT-1",
        actorId = "operator-1",
        actorType = "HUMAN",
        payload = """{"amount":100}""",
        sourceService = "openbank-payment-service",
        correlationId = "corr-1",
        occurredAt = Instant.parse("2026-08-21T10:00:00.123456Z"),
        recordedAt = Instant.parse("2026-08-21T10:00:01Z"),
        occurredAtSource = OccurredAtSource.EVENT,
        sourceServiceSource = AttributionSource.EVENT,
    )

    @Test
    fun `chain hash matches the vector the offline verifier reproduces`() {
        assertThat(AuditRepository.chainHash("0".repeat(64), entry))
            .isEqualTo("e00d6b6fe01bfdbd8808f85a89fdc56ef97c5c79f766142c18f0769abdeec0d1")
    }

    @Test
    fun `chain hash ignores nanoseconds the database cannot store`() {
        // Same row, plus nanosecond digits timestamptz truncates. The hash MUST be unchanged, or
        // the row is unverifiable the moment it is read back (#3505).
        val withNanos = entry.copy(occurredAt = Instant.parse("2026-08-21T10:00:00.123456789Z"))
        assertThat(AuditRepository.chainHash("0".repeat(64), withNanos))
            .isEqualTo(AuditRepository.chainHash("0".repeat(64), entry))
    }

    @Test
    fun `anchor digest matches the vector the offline verifier reproduces`() {
        assertThat(
            AuditAnchor.digest(
                lastEntryId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
                lastRecordHash = "a".repeat(64),
                chainedCount = 42,
                chainStatus = "INTACT",
                signedAt = Instant.parse("2026-08-21T11:00:00.500Z"),
            ),
        ).isEqualTo("c60dbd813438ecfc42924eca6a6b3fef33c4f03c9e16c1e2a9e0da184d11bad8")
    }

    @Test
    fun `anchor digest over an empty chain head matches the offline verifier`() {
        assertThat(
            AuditAnchor.digest(
                lastEntryId = null,
                lastRecordHash = null,
                chainedCount = 0,
                chainStatus = "INTACT",
                signedAt = Instant.parse("2026-08-21T11:00:00.500Z"),
            ),
        ).isEqualTo("33cea295a63962d8152fcc655b8d7ec6be3abec01e340f39aa183009ffbfbf88")
    }
}
