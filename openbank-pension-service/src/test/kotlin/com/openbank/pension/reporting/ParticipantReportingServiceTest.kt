// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.reporting

import com.openbank.pension.application.port.out.ParticipantReportingQueries
import com.openbank.pension.application.usecase.ParticipantReportingService
import com.openbank.pension.domain.reporting.AgeBand
import com.openbank.pension.domain.reporting.ContributionTotals
import com.openbank.pension.domain.reporting.InForceGroup
import com.openbank.pension.domain.reporting.MixedCurrencyException
import com.openbank.pension.domain.reporting.PayoutLine
import com.openbank.pension.domain.reporting.PayoutTotals
import com.openbank.pension.domain.reporting.StateContributionFigures
import com.openbank.pension.domain.reporting.TransferTotals
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ParticipantReportingServiceTest {

    private class Fake(val currencies: Set<String> = setOf("CZK")) : ParticipantReportingQueries {
        val contributionWindows = mutableListOf<Pair<LocalDate, LocalDate>>()
        override suspend fun currencies(from: LocalDate, to: LocalDate) = currencies
        override suspend fun inForce(asOf: LocalDate) = listOf(
            InForceGroup("DPS", 17, "ACTIVE", 1),
            InForceGroup("DPS", 18, "ACTIVE", 2),
            InForceGroup("DIP", 64, "SUSPENDED", 3),
            InForceGroup("DPS", 65, "ACTIVE", 4),
        )
        override suspend fun startedBetween(from: LocalDate, to: LocalDate) = 5L
        override suspend fun exitedBetween(from: LocalDate, to: LocalDate) = 6L
        override suspend fun contributingBetween(from: LocalDate, to: LocalDate) = 7L
        override suspend fun pensionersBetween(from: LocalDate, to: LocalDate) = 8L
        override suspend fun contributions(from: LocalDate, to: LocalDate): ContributionTotals {
            contributionWindows += from to to
            return ContributionTotals(BigDecimal("100"), BigDecimal("20"), BigDecimal("3"), BigDecimal("999"))
        }
        override suspend fun stateContributions(from: LocalDate, to: LocalDate) =
            StateContributionFigures(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO)
        override suspend fun payouts(from: LocalDate, to: LocalDate) = PayoutTotals(
            mapOf("LUMP_SUM" to PayoutLine(BigDecimal("10"), 1), "ANNUITY_PREMIUM" to PayoutLine(BigDecimal("5"), 1)),
            BigDecimal("2"),
            2,
        )
        override suspend fun transfers(from: LocalDate, to: LocalDate) =
            TransferTotals(1, BigDecimal.TEN, 0, BigDecimal.ZERO)
    }

    @Test
    fun `age bands are closed at both edges and every band is reported`() {
        assertThat(AgeBand.of(17)).isEqualTo(AgeBand.UNDER_18)
        assertThat(AgeBand.of(18)).isEqualTo(AgeBand.FROM_18_TO_34)
        assertThat(AgeBand.of(64)).isEqualTo(AgeBand.FROM_60_TO_64)
        assertThat(AgeBand.of(65)).isEqualTo(AgeBand.FROM_65)
        val a =
            runBlocking {
                ParticipantReportingService(
                    Fake(),
                    "CZK",
                ).aggregates(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-09-30"))
            }
        assertThat(a.participants.inForce).isEqualTo(10)
        assertThat(a.participants.byAgeBand).containsEntry("0-17", 1).containsEntry("18-34", 2)
            .containsEntry("60-64", 3).containsEntry("65+", 4).containsEntry("35-49", 0).hasSize(AgeBand.entries.size)
        assertThat(a.participants.byProductLine).containsEntry("DPS", 7).containsEntry("DIP", 3)
        assertThat(a.participants.byStatusAtPeriodEnd).containsEntry("ACTIVE", 7).containsEntry("SUSPENDED", 3)
    }

    @Test
    fun `contribution total excludes transfers in, payouts sum every form, ytd runs from 1 January`() {
        val fake = Fake()
        val a =
            runBlocking {
                ParticipantReportingService(
                    fake,
                    "CZK",
                ).aggregates(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-09-30"))
            }
        assertThat(a.contributions.total).isEqualByComparingTo("123")
        assertThat(a.payouts.total).isEqualByComparingTo("15")
        assertThat(fake.contributionWindows).containsExactly(
            LocalDate.parse("2026-07-01") to LocalDate.parse("2026-09-30"),
            LocalDate.parse("2026-01-01") to LocalDate.parse("2026-09-30"),
        )
    }

    @Test
    fun `a period booked in another currency is refused rather than summed`() {
        assertThatThrownBy {
            runBlocking {
                ParticipantReportingService(
                    Fake(setOf("CZK", "EUR")),
                    "CZK",
                ).aggregates(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-09-30"))
            }
        }.isInstanceOf(MixedCurrencyException::class.java)
        assertThatThrownBy {
            runBlocking {
                ParticipantReportingService(
                    Fake(),
                    "CZK",
                ).aggregates(LocalDate.parse("2026-09-30"), LocalDate.parse("2026-07-01"))
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
