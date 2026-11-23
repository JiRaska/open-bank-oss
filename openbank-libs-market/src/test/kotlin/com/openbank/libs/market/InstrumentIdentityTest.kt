// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.market

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InstrumentIdentityTest {
    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private val oldest = now.minusSeconds(60)
    private val subject = InstrumentSubject("synthetic-order-a", "subject-1")
    private val decision = IdentityVerification.Verified(subject, UUID.randomUUID(), "synthetic-proof", now, 7)

    @Test
    fun `a current verification authorises only its own instrument subject`() {
        assertTrue(decision.isCurrentFor(subject, decision, now, oldest))
        assertFalse(decision.isCurrentFor(subject.copy(instrumentRef = "synthetic-order-b"), decision, now, oldest))
        assertFalse(decision.isCurrentFor(subject.copy(subjectRef = "subject-2"), decision, now, oldest))
    }

    @Test
    fun `ambiguity and revoked proof refuse an earlier verified decision`() {
        assertFalse(decision.isCurrentFor(subject, IdentityVerification.Ambiguous, now, oldest))
        assertFalse(decision.isCurrentFor(subject, IdentityVerification.Unverified, now, oldest))
    }

    @Test
    fun `a merged party or new verification version requires a fresh decision`() {
        val merged = decision.copy(partyId = UUID.randomUUID())
        val newVersion = decision.copy(version = 8)
        assertFalse(decision.isCurrentFor(subject, merged, now, oldest))
        assertFalse(decision.isCurrentFor(subject, newVersion, now, oldest))
        assertTrue(newVersion.isCurrentFor(subject, newVersion, now, oldest))
    }

    @Test
    fun `verification outside its freshness window cannot authorise effect`() {
        val stale = decision.copy(verifiedAt = oldest.minusSeconds(1))
        val future = decision.copy(verifiedAt = now.plusSeconds(1))
        assertFalse(stale.isCurrentFor(subject, stale, now, oldest))
        assertFalse(future.isCurrentFor(subject, future, now, oldest))
    }
}
