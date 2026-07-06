// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Plain domain test (ADR-0002: zero framework imports) for [SanctionsCheck.isHighRisk]. */
class SanctionsCheckTest {

    @Test
    fun `isHighRisk is true for HIT regardless of score`() {
        assertThat(sampleCheck(status = SanctionsCheckStatus.HIT, overallScore = 0.10).isHighRisk()).isTrue()
    }

    @Test
    fun `isHighRisk is true for POTENTIAL_HIT above the 0-85 threshold`() {
        assertThat(sampleCheck(status = SanctionsCheckStatus.POTENTIAL_HIT, overallScore = 0.90).isHighRisk()).isTrue()
    }

    @Test
    fun `isHighRisk is false for POTENTIAL_HIT at or below the 0-85 threshold`() {
        assertThat(sampleCheck(status = SanctionsCheckStatus.POTENTIAL_HIT, overallScore = 0.85).isHighRisk()).isFalse()
        assertThat(sampleCheck(status = SanctionsCheckStatus.POTENTIAL_HIT, overallScore = 0.50).isHighRisk()).isFalse()
    }

    @Test
    fun `isHighRisk is false for CLEAR, WHITELISTED and ESCALATED`() {
        assertThat(sampleCheck(status = SanctionsCheckStatus.CLEAR, overallScore = 1.0).isHighRisk()).isFalse()
        assertThat(sampleCheck(status = SanctionsCheckStatus.WHITELISTED, overallScore = 1.0).isHighRisk()).isFalse()
        assertThat(sampleCheck(status = SanctionsCheckStatus.ESCALATED, overallScore = 1.0).isHighRisk()).isFalse()
    }

    private fun sampleCheck(status: SanctionsCheckStatus, overallScore: Double) = SanctionsCheck(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-1",
        entityType = EntityType.INDIVIDUAL,
        name = "Test Entity",
        aliases = emptyList(),
        dateOfBirth = null,
        nationality = null,
        identifiers = emptyMap(),
        status = status,
        matches = emptyList(),
        overallScore = overallScore,
        checkedLists = SanctionsListType.entries,
        reviewedBy = null,
        reviewNote = null,
        checkedAt = Instant.EPOCH,
        reviewedAt = null,
    )
}
