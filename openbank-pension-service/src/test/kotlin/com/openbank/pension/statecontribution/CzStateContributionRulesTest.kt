// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.statecontribution

import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.incentive.IncentiveEngine
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.statecontribution.CzClaimReasonCode
import com.openbank.pension.domain.statecontribution.CzStateContributionCalendar
import com.openbank.pension.domain.statecontribution.ReturnResultLine
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import com.openbank.pension.infrastructure.statecontribution.CzMfStateContributionFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Golden values for the CZ state contribution, each one traced to Act 427/2011
 * (`docs/research/cz-state-pension-contribution.md`, fact ids in the test names).
 */
class CzStateContributionRulesTest {

    private val pack = JurisdictionPackLoader.loadRegistry().pinned("CZ", ProductLine.DPS, 2)
    private val rule = pack.incentives.single { it.id == "state-contribution" }

    private fun monthly(amount: String): BigDecimal = IncentiveEngine.periodAmount(
        rule,
        if (BigDecimal(amount).signum() == 0) {
            emptyList()
        } else {
            listOf(
                Contribution(
                    id = UUID.randomUUID(), contractId = UUID.randomUUID(), paymentId = "p-$amount",
                    source = ContributionSource.PARTICIPANT, channel = ContributionChannel.BANK_TRANSFER,
                    amount = BigDecimal(amount), currency = "CZK", valueDate = LocalDate.of(2026, 1, 15),
                    receivedAt = Instant.EPOCH,
                ),
            )
        },
    )

    @ParameterizedTest(name = "contribution {0} CZK -> state contribution {1} CZK")
    @CsvSource(
        // below the 500 CZK minimum (A1)
        "0, 0", "499, 0", "499.99, 0",
        // 20 % band 500-1699 (A2), rounded DOWN to whole crowns (A5)
        "500, 100", "501, 100", "1000, 200", "1234, 246", "1699, 339", "1699.99, 339",
        // flat 340 from 1700 (A3)
        "1700, 340", "5000, 340", "100000, 340",
    )
    fun `A1-A5 golden per bracket`(contribution: String, expected: String) {
        assertThat(monthly(contribution)).isEqualByComparingTo(expected)
    }

    @Test
    fun `A5 the pack carries the whole-crown rounding and it is load-bearing`() {
        assertThat(rule.roundDownToUnit).isEqualByComparingTo("1")
        val unrounded = IncentiveEngine.periodAmount(rule.copy(roundDownToUnit = null), emptyList())
        assertThat(unrounded).isEqualByComparingTo("0")
        val raw = rule.copy(roundDownToUnit = null)
        assertThat(raw.roundDown(BigDecimal("246.80"))).isEqualByComparingTo("246.80")
        assertThat(rule.roundDown(BigDecimal("246.80"))).isEqualByComparingTo("246")
    }

    @Test
    fun `v1 is left exactly as contracts pinned it - the rounding arrives only in v2`() {
        val registry = JurisdictionPackLoader.loadRegistry()
        val v1 = registry.pinned("CZ", ProductLine.DPS, 1).incentives.single { it.id == "state-contribution" }
        assertThat(v1.roundDownToUnit).isNull()
        assertThat(v1.claimFormat).isEqualTo("agency-monthly-batch-v0")
        assertThat(registry.resolve("CZ", ProductLine.DPS, LocalDate.of(2026, 10, 9)).version).isEqualTo(2)
    }

    @Test
    fun `the pack stays under legal review and cites its source`() {
        assertThat(pack.legalReview.status.name).isEqualTo("REQUIRES_LEGAL_REVIEW")
        assertThat(pack.legalReview.sources.orEmpty().joinToString()).contains("zakonyprolidi.cz/cs/2011-427")
        assertThat(rule.claimFormat).isEqualTo(CzMfStateContributionFormat.FORMAT)
    }

    @Test
    fun `C2 P2 a quarter is filed in the following month and paid by the end of the second month after it`() {
        val feb = YearMonth.of(2026, 2)
        assertThat(CzStateContributionCalendar.quarterStart(feb)).isEqualTo(YearMonth.of(2026, 1))
        assertThat(CzStateContributionCalendar.filingMonth(feb)).isEqualTo(YearMonth.of(2026, 4))
        assertThat(CzStateContributionCalendar.filingDeadline(feb)).isEqualTo(LocalDate.of(2026, 4, 30))
        assertThat(CzStateContributionCalendar.fileable(feb, LocalDate.of(2026, 3, 31))).isFalse()
        assertThat(CzStateContributionCalendar.fileable(feb, LocalDate.of(2026, 4, 1))).isTrue()
        assertThat(CzStateContributionCalendar.expectedPaymentBy(feb)).isEqualTo(LocalDate.of(2026, 5, 31))
        assertThat(CzStateContributionCalendar.filingMonth(YearMonth.of(2026, 11))).isEqualTo(YearMonth.of(2027, 1))
    }

    @Test
    fun `R1-R5 return deadlines`() {
        // R1: one month after discovery, end of that month
        assertThat(
            CzStateContributionCalendar.unlawfulReturnDue(LocalDate.of(2026, 1, 31)),
        ).isEqualTo(LocalDate.of(2026, 2, 28))
        // R2: six months after termination, end of that month
        assertThat(
            CzStateContributionCalendar.terminationReturnDue(LocalDate.of(2026, 3, 15)),
        ).isEqualTo(LocalDate.of(2026, 9, 30))
        assertThat(
            CzStateContributionCalendar.returnReportDeadline(YearMonth.of(2026, 5)),
        ).isEqualTo(LocalDate.of(2026, 5, 10))
        assertThat(
            CzStateContributionCalendar.returnResultExpectedBy(YearMonth.of(2026, 5)),
        ).isEqualTo(LocalDate.of(2026, 6, 20))
        assertThat(
            CzStateContributionCalendar.returnSettlementDue(LocalDate.of(2026, 6, 18)),
        ).isEqualTo(LocalDate.of(2026, 6, 30))
    }

    @Test
    fun `P1 a result whose lines do not add up to the aggregate payment is refused whole`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val good = "R;cz-mf-state-contribution-v1;RESULT;2026;1;440\nP;$a;340\nQ;$b;100;AMOUNT_RECOMPUTED"
        val lines = CzMfStateContributionFormat.parseApplicationResult(good)
        assertThat(lines.map { it.amount }).containsExactly(BigDecimal("340"), BigDecimal("100"))
        assertThat(lines[1].reason).isEqualTo("AMOUNT_RECOMPUTED")
        assertThatThrownBy {
            CzMfStateContributionFormat.parseApplicationResult(good.replace(";440", ";500"))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("aggregate")
        assertThatThrownBy {
            CzMfStateContributionFormat.parseApplicationResult("R;agency-monthly-batch-v0;RESULT;2026;1;0")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `T3 an unknown agency reason code is kept as OTHER, never dropped`() {
        val id = UUID.randomUUID()
        val line = CzMfStateContributionFormat.parseApplicationResult(
            "R;cz-mf-state-contribution-v1;RESULT;2026;1;0\nX;$id;Z99",
        )
            .single()
        assertThat(line.accepted).isFalse()
        assertThat(line.reason).startsWith("OTHER:")
        assertThat(CzClaimReasonCode.parse("OLD_AGE_PENSIONER")).isEqualTo(CzClaimReasonCode.OLD_AGE_PENSIONER)
        val returns = CzMfStateContributionFormat.parseReturnResult(
            "R;cz-mf-state-contribution-v1;RETURNS-RESULT;2026-05\nE;$id;DATA_ERROR",
        )
        assertThat(returns.single()).isEqualTo(ReturnResultLine.Refused(id, CzClaimReasonCode.DATA_ERROR))
    }
}
