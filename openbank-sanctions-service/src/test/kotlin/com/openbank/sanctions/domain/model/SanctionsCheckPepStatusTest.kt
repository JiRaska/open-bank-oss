// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID

/**
 * `pepStatus` shares the check-status vocabulary instead of declaring a second enum, so the
 * narrowing the separate type used to give has to be an invariant: a PEP outcome is never
 * WHITELISTED or ESCALATED.
 */
class SanctionsCheckPepStatusTest {

    private fun check(pepStatus: SanctionsCheckStatus?) = SanctionsCheck(
        id = UUID.randomUUID(), idempotencyKey = "k", entityType = EntityType.INDIVIDUAL, name = "Synthetic",
        aliases = emptyList(), dateOfBirth = null, nationality = null, identifiers = emptyMap(),
        status = SanctionsCheckStatus.CLEAR, matches = emptyList(), overallScore = 0.0,
        checkedLists = listOf(SanctionsListType.PEP_GLOBAL), reviewedBy = null, reviewNote = null,
        checkedAt = Instant.parse("2026-11-11T10:00:00Z"), reviewedAt = null,
        profile = ScreeningProfile.CUSTOMER, pepStatus = pepStatus,
    )

    @ParameterizedTest
    @EnumSource(value = SanctionsCheckStatus::class, names = ["CLEAR", "POTENTIAL_HIT", "HIT"])
    fun `a PEP outcome may be clear, a potential hit or a hit`(status: SanctionsCheckStatus) {
        assertThat(check(status).pepStatus).isEqualTo(status)
    }

    @ParameterizedTest
    @EnumSource(
        value = SanctionsCheckStatus::class,
        names = ["CLEAR", "POTENTIAL_HIT", "HIT"],
        mode = EnumSource.Mode.EXCLUDE,
    )
    fun `a PEP outcome is never a review state`(status: SanctionsCheckStatus) {
        assertThatThrownBy { check(status) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("pepStatus")
    }
}
