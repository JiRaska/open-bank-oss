// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain

import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ClawbackMode
import com.openbank.pension.domain.pack.IncentivePeriod
import com.openbank.pension.domain.pack.IncentiveRule
import com.openbank.pension.domain.pack.IncentiveType
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.LegalReviewStatus
import com.openbank.pension.domain.pack.PackEvaluator
import com.openbank.pension.domain.pack.PackNotFoundException
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.pack.SurrenderCalculator
import com.openbank.pension.domain.pack.SurrenderInputs
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Evaluates the shipped reference packs. The numbers asserted here restate the pack DATA, not law:
 * both packs are still marked REQUIRES_LEGAL_REVIEW, which one test pins so that marking cannot be
 * dropped without a deliberate change here.
 */
class JurisdictionPackTest {

    private val registry = JurisdictionPackLoader.loadRegistry()
    private val on = LocalDate.parse("2026-10-09")
    private val dps = registry.resolve("CZ", ProductLine.DPS, on)
    private val dip = registry.resolve("CZ", ProductLine.DIP, on)

    private fun result(pack: JurisdictionPack, amount: String, used: Map<String, BigDecimal> = emptyMap()) =
        PackEvaluator.evaluateIncentives(pack, BigDecimal(amount), IncentivePeriod.MONTH, BigDecimal("60000"), used)
            .associateBy { it.incentiveId }

    @Test
    fun `both reference packs load and are marked as requiring legal review`() {
        assertThat(registry.all()).hasSize(2)
        assertThat(registry.all().map { it.legalReview.status }).containsOnly(LegalReviewStatus.REQUIRES_LEGAL_REVIEW)
    }

    @Test
    fun `no pack effective before its start fails closed`() {
        assertThatThrownBy { registry.resolve("CZ", ProductLine.DPS, LocalDate.parse("2000-01-01")) }
            .isInstanceOf(PackNotFoundException::class.java)
        assertThatThrownBy {
            registry.resolve("ZZ", ProductLine.DPS, on)
        }.isInstanceOf(PackNotFoundException::class.java)
    }

    @Test
    fun `matching incentive is zero below the band, proportional inside it and capped above it`() {
        assertThat(result(dps, "499")["state-contribution"]!!.amount).isEqualByComparingTo("0")
        assertThat(result(dps, "1000")["state-contribution"]!!.amount).isEqualByComparingTo("200")
        assertThat(result(dps, "5000")["state-contribution"]!!.amount).isEqualByComparingTo("340")
    }

    @Test
    fun `tax relief counts only the annual contribution above the threshold, within the shared cap`() {
        // 3000/month = 36000/yr; 36000 - 20400 = 15600 deductible.
        assertThat(result(dps, "3000")["income-tax-deduction"]!!.amount).isEqualByComparingTo("15600")
        // The same group already consumed 40000 elsewhere: only 8000 remains.
        val shared = result(dps, "3000", mapOf("retirement-products-deduction" to BigDecimal("40000")))
        assertThat(shared["income-tax-deduction"]!!.amount).isEqualByComparingTo("8000")
        assertThat(shared["income-tax-deduction"]!!.indicativeSaving).isEqualByComparingTo("1200")
    }

    @Test
    fun `the long-term investment line has no matching incentive and no threshold`() {
        val r = result(dip, "3000")
        assertThat(r).doesNotContainKey("state-contribution")
        assertThat(r["income-tax-deduction"]!!.amount).isEqualByComparingTo("36000")
        assertThat(r["employer-contribution-exemption"]!!.amount).isEqualByComparingTo("50000")
    }

    @Test
    fun `permitted provider types differ per line`() {
        assertThat(dps.permittedProviderTypes).containsExactly(ProviderType.PENSION_COMPANY)
        assertThat(dip.permittedProviderTypes).contains(ProviderType.BANK)
    }

    @Test
    fun `eligibility checks age and residency`() {
        val adult = LocalDate.parse("1990-01-01")
        assertThat(PackEvaluator.checkEligibility(dps, adult, "CZ", emptySet(), false, on).eligible).isTrue()
        assertThat(PackEvaluator.checkEligibility(dps, adult, "DE", emptySet(), false, on).eligible).isFalse()
        assertThat(
            PackEvaluator.checkEligibility(dps, adult, "DE", setOf("PUBLIC_HEALTH_INSURANCE_CZ"), false, on).eligible,
        ).isTrue()
        val minor = LocalDate.parse("2015-01-01")
        assertThat(PackEvaluator.checkEligibility(dps, minor, "CZ", emptySet(), false, on).eligible).isFalse()
        assertThat(PackEvaluator.checkEligibility(dps, minor, "CZ", emptySet(), true, on).eligible).isTrue()
        assertThat(PackEvaluator.checkEligibility(dip, minor, null, emptySet(), true, on).eligible).isFalse()
    }

    @Test
    fun `an early exit claws back state contributions and recent tax relief`() {
        val contract = PensionContract.draft(
            UUID.randomUUID(), ProductLine.DPS, "CZ", 1, UUID.randomUUID(), ProviderType.PENSION_COMPANY,
            LocalDate.parse(
                "1990-01-01",
            ),
            ContributionSchedule(BigDecimal("1700"), "CZK", ContributionFrequency.MONTHLY),
            "BALANCED", emptyList(), LocalDate.parse("2020-01-01"), Instant.EPOCH,
        ).submit(Instant.EPOCH).activate(LocalDate.parse("2020-01-01"), Instant.EPOCH)
        val preview = SurrenderCalculator.preview(
            contract,
            dps,
            SurrenderInputs(
                currentValue = BigDecimal("150000"),
                incentivesReceived = mapOf(
                    "state-contribution" to mapOf(2020 to BigDecimal("2760"), 2025 to BigDecimal("4080")),
                    "income-tax-deduction" to mapOf(2010 to BigDecimal("999"), 2025 to BigDecimal("2340")),
                ),
            ),
            on,
        )
        assertThat(preview.payoutConditionsMet).isFalse()
        val claws = preview.clawbacks.associateBy { it.incentiveId }
        assertThat(claws["state-contribution"]!!.amount).isEqualByComparingTo("6840")
        assertThat(claws["state-contribution"]!!.mode).isEqualTo(ClawbackMode.RETURN_ALL)
        assertThat(claws["income-tax-deduction"]!!.amount).isEqualByComparingTo("2340")
        assertThat(preview.estimatedNetPayout).isEqualByComparingTo("140820")
    }

    @Test
    fun `a rule missing a field its type needs is refused at construction`() {
        assertThatThrownBy { IncentiveRule(id = "m", type = IncentiveType.MATCHING, rate = BigDecimal.ONE) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("amountCap")
    }
}
