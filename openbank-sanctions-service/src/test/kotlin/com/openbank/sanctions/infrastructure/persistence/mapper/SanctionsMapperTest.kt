// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.mapper

import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MatchType
import com.openbank.sanctions.domain.model.SanctionsCheck
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.SanctionsMatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Pure JSON round-trip mapping between the domain [SanctionsCheck] and its JPA entity. */
class SanctionsMapperTest {

    @Test
    fun `toEntity followed by toDomain round-trips a check with matches and identifiers`() {
        val check = SanctionsCheck(
            id = UUID.randomUUID(),
            idempotencyKey = "idem-42",
            entityType = EntityType.INDIVIDUAL,
            name = "Vladimir Putin",
            aliases = listOf("Vova Putin", "V. Putin"),
            dateOfBirth = "1952-10-07",
            nationality = "RU",
            identifiers = mapOf("passport" to "123456", "taxId" to "999-88"),
            status = SanctionsCheckStatus.HIT,
            matches = listOf(
                SanctionsMatch(
                    listType = SanctionsListType.OFAC_SDN,
                    matchType = MatchType.EXACT,
                    matchScore = 0.97,
                    matchedName = "Vladimir Putin",
                    matchedId = "ofac-17766",
                    listEntryDate = null,
                    programs = listOf("RUSSIA-EO14024"),
                ),
            ),
            overallScore = 0.97,
            checkedLists = listOf(SanctionsListType.OFAC_SDN, SanctionsListType.EU_CONSOLIDATED),
            reviewedBy = "analyst-1",
            reviewNote = "confirmed hit",
            checkedAt = Instant.parse("2024-01-15T12:00:00Z"),
            reviewedAt = Instant.parse("2024-01-16T09:30:00Z"),
        )

        val entity = check.toEntity()
        assertThat(entity.id).isEqualTo(check.id)
        assertThat(entity.idempotencyKey).isEqualTo("idem-42")
        assertThat(entity.aliasesJson).contains("Vova Putin")
        assertThat(entity.identifiersJson).contains("passport")
        assertThat(entity.matchesJson).contains("ofac-17766")
        assertThat(entity.checkedListsJson).contains("OFAC_SDN")

        val roundTripped = entity.toDomain()
        assertThat(roundTripped).isEqualTo(check)
    }

    @Test
    fun `toEntity followed by toDomain round-trips a check with empty collections and nulls`() {
        val check = SanctionsCheck(
            id = UUID.randomUUID(),
            idempotencyKey = "idem-clear",
            entityType = EntityType.ORGANIZATION,
            name = "Acme Corp",
            aliases = emptyList(),
            dateOfBirth = null,
            nationality = null,
            identifiers = emptyMap(),
            status = SanctionsCheckStatus.CLEAR,
            matches = emptyList(),
            overallScore = 0.0,
            checkedLists = SanctionsListType.entries,
            reviewedBy = null,
            reviewNote = null,
            checkedAt = Instant.EPOCH,
            reviewedAt = null,
        )

        val roundTripped = check.toEntity().toDomain()

        assertThat(roundTripped).isEqualTo(check)
        assertThat(roundTripped.aliases).isEmpty()
        assertThat(roundTripped.reviewedBy).isNull()
    }
}
