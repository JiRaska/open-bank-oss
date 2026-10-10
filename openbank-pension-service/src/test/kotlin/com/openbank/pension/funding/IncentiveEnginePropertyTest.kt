// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.funding

import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.incentive.ClawbackKind
import com.openbank.pension.domain.incentive.ContractYearInput
import com.openbank.pension.domain.incentive.IncentiveEngine
import com.openbank.pension.domain.incentive.IncentiveLedgerEntry
import com.openbank.pension.domain.incentive.LedgerEntryKind
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ClaimChannel
import com.openbank.pension.domain.pack.ClawbackMode
import com.openbank.pension.domain.pack.ClawbackRule
import com.openbank.pension.domain.pack.Eligibility
import com.openbank.pension.domain.pack.IncentiveBand
import com.openbank.pension.domain.pack.IncentivePeriod
import com.openbank.pension.domain.pack.IncentiveRule
import com.openbank.pension.domain.pack.IncentiveType
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.LegalReview
import com.openbank.pension.domain.pack.LegalReviewStatus
import com.openbank.pension.domain.pack.PayoutConditions
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.pack.ResidencyRule
import com.openbank.pension.domain.pack.TransferRules
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.random.Random

/**
 * Property tests of the generic incentive engine, across every rule type, over generated packs
 * and contributions. A fixed seed per test keeps a failure reproducible; each property runs
 * [RUNS] cases. Every property here was checked to FAIL against a deliberately broken engine
 * (cap removed, shared-cap consumption skipped, clawback window inverted) before being trusted.
 */
class IncentiveEnginePropertyTest {

    private fun money(r: Random, max: Int) = BigDecimal(r.nextInt(0, max * 100)).movePointLeft(2)

    private fun pack(rules: List<IncentiveRule>) = JurisdictionPack(
        jurisdiction = "XX",
        productLine = ProductLine.DPS,
        version = 1,
        effectiveFrom = LocalDate.of(2020, 1, 1),
        currency = "EUR",
        legalReview = LegalReview(LegalReviewStatus.REQUIRES_LEGAL_REVIEW),
        eligibility = Eligibility(18, residency = ResidencyRule(false)),
        permittedProviderTypes = setOf(ProviderType.PENSION_COMPANY),
        incentives = rules,
        payout = PayoutConditions(60, 120, setOf(PayoutForm.LUMP_SUM), true),
        transfer = TransferRules(true),
    )

    private fun participant(contractId: UUID, amount: BigDecimal, date: LocalDate, n: Int = 0) = Contribution(
        UUID.randomUUID(), contractId, "p-$contractId-$date-$n-${UUID.randomUUID()}", ContributionSource.PARTICIPANT,
        ContributionChannel.STANDING_ORDER, amount, "EUR", date, receivedAt = Instant.EPOCH,
    )

    private fun employer(contractId: UUID, employer: UUID, amount: BigDecimal, date: LocalDate) = Contribution(
        UUID.randomUUID(), contractId, "e-${UUID.randomUUID()}", ContributionSource.EMPLOYER,
        ContributionChannel.EMPLOYER_BATCH, amount, "EUR", date, employerPartyId = employer, receivedAt = Instant.EPOCH,
    )

    @Test
    fun `matching is never negative, never above the cap, and monotonic in the contribution`() {
        val r = Random(SEED)
        repeat(RUNS) {
            val rule = IncentiveRule(
                id = "m",
                type = IncentiveType.MATCHING,
                period = IncentivePeriod.MONTH,
                rate = BigDecimal(r.nextInt(0, 101)).movePointLeft(2),
                minContribution = money(r, 1000),
                amountCap = money(r, 500),
            )
            val a = money(r, 5000)
            val b = a + money(r, 5000)
            val c = UUID.randomUUID()
            val day = LocalDate.of(2026, 1, 15)
            val ia = IncentiveEngine.periodAmount(rule, listOf(participant(c, a.max(BigDecimal("0.01")), day)))
            val ib = IncentiveEngine.periodAmount(rule, listOf(participant(c, b.max(BigDecimal("0.01")), day)))
            assertThat(ia.signum()).isGreaterThanOrEqualTo(0)
            assertThat(ib).isLessThanOrEqualTo(rule.amountCap!!.setScale(2))
            assertThat(ib).isGreaterThanOrEqualTo(ia)
        }
    }

    @Test
    fun `banded matching equals the sum of each band's portion and never exceeds the cap`() {
        val r = Random(SEED + 1)
        repeat(RUNS) {
            val edge1 = BigDecimal(r.nextInt(1, 1000))
            val edge2 = edge1 + BigDecimal(r.nextInt(1, 1000))
            val bands = listOf(
                IncentiveBand(BigDecimal.ZERO, edge1, BigDecimal("0.30")),
                IncentiveBand(edge1, edge2, BigDecimal("0.10")),
                IncentiveBand(edge2, null, BigDecimal("0.00")),
            )
            val cap = money(r, 1000) + BigDecimal.ONE
            val rule = IncentiveRule("b", IncentiveType.MATCHING, IncentivePeriod.MONTH, amountCap = cap, bands = bands)
            val x = money(r, 4000) + BigDecimal.ONE
            val expected = bands.fold(BigDecimal.ZERO) { acc, band ->
                acc + band.portionOf(x).multiply(band.rate)
            }.min(cap)
            val got = IncentiveEngine.periodAmount(
                rule,
                listOf(participant(UUID.randomUUID(), x, LocalDate.of(2026, 3, 1))),
            )
            assertThat(got).isEqualByComparingTo(IncentiveEngine.money(expected))
        }
    }

    @Test
    fun `flat pays exactly the flat amount at or above the minimum and nothing below`() {
        val r = Random(SEED + 2)
        repeat(RUNS) {
            val min = money(r, 1000)
            val flat = money(r, 300) + BigDecimal.ONE
            val rule =
                IncentiveRule("f", IncentiveType.FLAT, IncentivePeriod.MONTH, flatAmount = flat, minContribution = min)
            val x = money(r, 2000) + BigDecimal("0.01")
            val got = IncentiveEngine.periodAmount(
                rule,
                listOf(participant(UUID.randomUUID(), x, LocalDate.of(2026, 3, 1))),
            )
            assertThat(got).isEqualByComparingTo(if (x >= min) flat else BigDecimal.ZERO)
        }
    }

    @Test
    fun `only participant money earns a periodic incentive`() {
        val rule =
            IncentiveRule(
                "m",
                IncentiveType.MATCHING,
                IncentivePeriod.MONTH,
                rate = BigDecimal("0.2"),
                amountCap = BigDecimal("1000"),
            )
        val c = UUID.randomUUID()
        val employerOnly = listOf(employer(c, UUID.randomUUID(), BigDecimal("2000"), LocalDate.of(2026, 1, 3)))
        assertThat(IncentiveEngine.periodAmount(rule, employerOnly)).isEqualByComparingTo("0")
    }

    @Test
    fun `shared caps are never over-allocated across contracts and no contract gets more than alone`() {
        val r = Random(SEED + 3)
        repeat(RUNS) {
            val cap = money(r, 50_000) + BigDecimal.ONE
            val threshold = money(r, 20_000)
            val externalUsed = money(r, 60_000)
            val tax = IncentiveRule(
                "t",
                IncentiveType.TAX_RELIEF,
                threshold = threshold,
                annualCap = cap,
                sharedCapGroup = "g",
                indicativeTaxRate = BigDecimal("0.15"),
                claimChannel = ClaimChannel.TAX_RETURN,
            )
            val p = pack(listOf(tax))
            val inputs = (1..r.nextInt(1, 5)).map {
                ContractYearInput(UUID.randomUUID(), p, money(r, 80_000), emptyList())
            }
            val alloc = IncentiveEngine.allocateTaxYear(inputs, mapOf("g" to externalUsed))
            val total = alloc.values.fold(BigDecimal.ZERO) { a, x -> a + x.deductible }
            assertThat(total).isLessThanOrEqualTo((cap - externalUsed).max(BigDecimal.ZERO).setScale(2))
            inputs.forEach { input ->
                val alone = (input.participantAnnual - threshold).max(BigDecimal.ZERO).min(cap)
                assertThat(
                    alloc.getValue(input.contractId).deductible,
                ).isLessThanOrEqualTo(IncentiveEngine.money(alone))
                assertThat(alloc.getValue(input.contractId).deductible.signum()).isGreaterThanOrEqualTo(0)
            }
        }
    }

    @Test
    fun `employer exemption splits every employer's money into exempt plus taxable within the shared cap`() {
        val r = Random(SEED + 4)
        repeat(RUNS) {
            val cap = money(r, 50_000) + BigDecimal.ONE
            val rule = IncentiveRule("e", IncentiveType.EMPLOYER_EXEMPTION, annualCap = cap, sharedCapGroup = "emp")
            val p = pack(listOf(rule))
            val employers = List(r.nextInt(1, 4)) { UUID.randomUUID() }
            val inputs = (1..r.nextInt(1, 4)).map {
                val id = UUID.randomUUID()
                val list = (1..r.nextInt(1, 8)).map { m ->
                    employer(id, employers.random(r), money(r, 15_000) + BigDecimal.ONE, LocalDate.of(2026, m, 1))
                }
                ContractYearInput(id, p, BigDecimal.ZERO, list)
            }
            val alloc = IncentiveEngine.allocateTaxYear(inputs, emptyMap())
            var exemptTotal = BigDecimal.ZERO
            inputs.forEach { input ->
                val ex = alloc.getValue(input.contractId).employerExemptions
                ex.forEach { assertThat(it.exempt + it.taxable).isEqualByComparingTo(it.contributed) }
                val contributed = input.employerContributions.fold(BigDecimal.ZERO) { a, c -> a + c.amount }
                assertThat(ex.fold(BigDecimal.ZERO) { a, e -> a + e.contributed }).isEqualByComparingTo(contributed)
                exemptTotal += ex.fold(BigDecimal.ZERO) { a, e -> a + e.exempt }
            }
            assertThat(exemptTotal).isLessThanOrEqualTo(cap.setScale(2))
        }
    }

    @Test
    fun `clawback returns the net ledger balance in the window and recaptures deductions per mode`() {
        val r = Random(SEED + 5)
        repeat(RUNS) {
            val years = r.nextInt(1, 6)
            val state = IncentiveRule(
                "s",
                IncentiveType.MATCHING,
                IncentivePeriod.MONTH,
                rate = BigDecimal("0.2"),
                amountCap = BigDecimal("340"),
                claimChannel = ClaimChannel.STATE_AGENCY_BATCH,
                clawback = ClawbackRule(ClawbackMode.RETURN_ALL),
            )
            val tax = IncentiveRule(
                "t",
                IncentiveType.TAX_RELIEF,
                annualCap = BigDecimal("48000"),
                clawback = ClawbackRule(ClawbackMode.RECAPTURE_YEARS, years),
            )
            val p = pack(listOf(state, tax))
            val contract = UUID.randomUUID()
            val entries = (1..r.nextInt(1, 30)).map {
                val y = r.nextInt(2010, 2027)
                IncentiveLedgerEntry(
                    UUID.randomUUID(), contract, "s", null,
                    if (r.nextInt(5) == 0) LedgerEntryKind.RETURNED else LedgerEntryKind.RECEIVED,
                    money(r, 340) + BigDecimal("0.01"), y, YearMonth.of(y, 1), Instant.EPOCH,
                )
            }
            val deductible = (2010..2026).associateWith { money(r, 48_000) }
            val items = IncentiveEngine.clawback(p, entries, deductible, 2026)
            val net = entries.fold(BigDecimal.ZERO) { a, e -> a + e.signed }
            val stateItem = items.firstOrNull { it.kind == ClawbackKind.RETURN_TO_AGENCY }
            if (net.signum() >
                0
            ) {
                assertThat(stateItem!!.amount).isEqualByComparingTo(net)
            } else {
                assertThat(stateItem).isNull()
            }
            val window = deductible.filterKeys { it > 2026 - years }.values.fold(BigDecimal.ZERO, BigDecimal::add)
            val taxItem = items.firstOrNull { it.kind == ClawbackKind.TAX_RECAPTURE }
            if (window.signum() > 0) {
                assertThat(taxItem!!.amount).isEqualByComparingTo(window)
                assertThat(taxItem.taxYears).allMatch { it > 2026 - years }
            } else {
                assertThat(taxItem).isNull()
            }
        }
    }

    @Test
    fun `quarterly and yearly periods start where the calendar says`() {
        assertThat(
            IncentiveEngine.periodStart(YearMonth.of(2026, 5), IncentivePeriod.QUARTER),
        ).isEqualTo(YearMonth.of(2026, 4))
        assertThat(
            IncentiveEngine.periodStart(YearMonth.of(2026, 12), IncentivePeriod.QUARTER),
        ).isEqualTo(YearMonth.of(2026, 10))
        assertThat(
            IncentiveEngine.periodStart(YearMonth.of(2026, 7), IncentivePeriod.YEAR),
        ).isEqualTo(YearMonth.of(2026, 1))
    }

    @Test
    fun `overlapping bands are refused at load`() {
        assertThatThrownBy {
            IncentiveRule(
                "x",
                IncentiveType.MATCHING,
                amountCap = BigDecimal.TEN,
                bands = listOf(
                    IncentiveBand(BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ONE),
                    IncentiveBand(BigDecimal.ONE, null, BigDecimal.ONE),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val SEED = 334
        const val RUNS = 500
    }
}
