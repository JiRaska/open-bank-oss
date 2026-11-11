// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.json

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MatchKind
import com.openbank.sanctions.domain.model.MatchType
import com.openbank.sanctions.domain.model.PepStatus
import com.openbank.sanctions.domain.model.SanctionsCheck
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.SanctionsMatch
import com.openbank.sanctions.domain.model.ScreeningProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The outbox event and the stored payload are written by hand-built mappers, not by the REST
 * layer, so the "absent, not null" rule has to hold for them too — the REST test cannot see it.
 */
class ScreeningProfileJsonTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules().omitAbsentScreeningProfileFields()

    private val match =
        SanctionsMatch(SanctionsListType.OFAC_SDN, MatchType.EXACT, 0.97, "Synthetic", "x-1", null, emptyList())
    private val legacy = SanctionsCheck(
        id = UUID.randomUUID(), idempotencyKey = "k", entityType = EntityType.INDIVIDUAL, name = "Synthetic",
        aliases = emptyList(), dateOfBirth = null, nationality = null, identifiers = emptyMap(),
        status = SanctionsCheckStatus.HIT, matches = listOf(match), overallScore = 0.97,
        checkedLists = listOf(SanctionsListType.OFAC_SDN), reviewedBy = null, reviewNote = null,
        checkedAt = Instant.parse("2026-11-11T10:00:00Z"), reviewedAt = null,
    )

    @Test
    fun `a profile-less check serialises without any of the new fields`() {
        val json = mapper.readTree(mapper.writeValueAsString(legacy))

        assertThat(json.has("profile") || json.has("country") || json.has("bankIdentifier") || json.has("pepStatus"))
            .isFalse()
        assertThat(json.path("matches")[0].has("matchKind")).isFalse()
        // Fields that were always present stay present, null included.
        assertThat(json.has("reviewedBy")).isTrue()
    }

    @Test
    fun `a profiled check serialises the new fields`() {
        val profiled = legacy.copy(
            profile = ScreeningProfile.CUSTOMER,
            country = "XE",
            bankIdentifier = "TESTXEAA",
            pepStatus = PepStatus.CLEAR,
            matches = listOf(match.copy(matchKind = MatchKind.SANCTIONS)),
        )

        val json = mapper.readTree(mapper.writeValueAsString(profiled))

        assertThat(json.path("profile").asText()).isEqualTo("CUSTOMER")
        assertThat(json.path("country").asText()).isEqualTo("XE")
        assertThat(json.path("bankIdentifier").asText()).isEqualTo("TESTXEAA")
        assertThat(json.path("pepStatus").asText()).isEqualTo("CLEAR")
        assertThat(json.path("matches")[0].path("matchKind").asText()).isEqualTo("SANCTIONS")
        assertThat(
            mapper.readValue(mapper.writeValueAsString(profiled), SanctionsCheck::class.java),
        ).isEqualTo(profiled)
    }
}
