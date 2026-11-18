// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.SanctionsJurisdictionRepository
import com.openbank.sanctions.domain.model.JurisdictionMeasure
import com.openbank.sanctions.domain.model.SanctionsJurisdiction
import com.openbank.sanctions.domain.model.SanctionsListType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which jurisdiction measures a screen turns into a finding. A RISK_FACTOR row (the FATF grey
 * list) must produce none, or every payment to a grey-listed country becomes a review case.
 */
class JurisdictionMeasureScreeningTest {

    private fun evaluatorHolding(vararg rows: SanctionsJurisdiction) = ScreeningProfileEvaluator(
        object : SanctionsJurisdictionRepository {
            override suspend fun findActive(
                countryCodes: Set<String>,
                listTypes: List<SanctionsListType>,
            ): List<SanctionsJurisdiction> = rows.filter { it.countryCode in countryCodes }
        },
    )

    private fun row(country: String, measure: JurisdictionMeasure, source: String) =
        SanctionsJurisdiction(SanctionsListType.FATF_HIGH_RISK, country, measure, 1, source)

    @Test
    fun `a RISK_FACTOR row is no screen finding`(): Unit = runBlocking {
        val matches = evaluatorHolding(row("XG", JurisdictionMeasure.RISK_FACTOR, "FATF_INCREASED_MONITORING"))
            .screenJurisdictions("XG", null, listOf(SanctionsListType.FATF_HIGH_RISK))
        assertThat(matches).isEmpty()
    }

    @Test
    fun `control - a HIGH_RISK row for the same country is a review-band finding`(): Unit = runBlocking {
        val matches = evaluatorHolding(row("XG", JurisdictionMeasure.HIGH_RISK, "FATF_CALL_FOR_ACTION"))
            .screenJurisdictions("XG", null, listOf(SanctionsListType.FATF_HIGH_RISK))
        assertThat(matches.map { it.matchScore }).containsExactly(ScreeningProfileEvaluator.POTENTIAL_HIT_THRESHOLD)
    }

    @Test
    fun `a RISK_FACTOR row next to a HIGH_RISK row leaves only the HIGH_RISK finding`(): Unit = runBlocking {
        val matches = evaluatorHolding(
            row("XG", JurisdictionMeasure.RISK_FACTOR, "FATF_INCREASED_MONITORING"),
            row("XG", JurisdictionMeasure.HIGH_RISK, "EU_HIGH_RISK_THIRD_COUNTRIES"),
        ).screenJurisdictions("XG", null, listOf(SanctionsListType.FATF_HIGH_RISK))
        assertThat(matches.map { it.programs[1] }).containsExactly("EU_HIGH_RISK_THIRD_COUNTRIES")
    }

    @Test
    fun `only RISK_FACTOR is excluded from screening`() {
        assertThat(
            JurisdictionMeasure.entries.filterNot {
                it.screens
            },
        ).containsExactly(JurisdictionMeasure.RISK_FACTOR)
    }
}
