// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.IncentivePeriod
import com.openbank.pension.domain.pack.IncentiveRule
import com.openbank.pension.domain.pack.IncentiveType
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.LegalReviewStatus
import com.openbank.pension.domain.pack.PackEvaluator
import com.openbank.pension.domain.pack.PackNotFoundException
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

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
    fun `all reference packs load and are marked as requiring legal review`() {
        assertThat(registry.all()).hasSize(3)
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
    fun `a rule missing a field its type needs is refused at construction`() {
        assertThatThrownBy { IncentiveRule(id = "m", type = IncentiveType.MATCHING, rate = BigDecimal.ONE) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("amountCap")
    }

    @Test
    fun `exactly one CZ DPS version is in force on any date - v1 ends the day before v2 starts`() {
        val v1 = registry.pinned("CZ", ProductLine.DPS, 1)
        val v2 = registry.pinned("CZ", ProductLine.DPS, 2)
        assertThat(v1.effectiveTo).isEqualTo(v2.effectiveFrom.minusDays(1))
        assertThat(registry.resolve("CZ", ProductLine.DPS, v1.effectiveTo!!).version).isEqualTo(1)
        assertThat(registry.resolve("CZ", ProductLine.DPS, v2.effectiveFrom).version).isEqualTo(2)
        // effectiveTo is inclusive: v1 is still in force on its last day and not the day after.
        assertThat(v1.isEffectiveOn(v1.effectiveTo!!)).isTrue()
        assertThat(v1.isEffectiveOn(v2.effectiveFrom)).isFalse()
    }

    @Test
    fun `a registry with two versions effective at once is refused`() {
        val v1 = registry.pinned("CZ", ProductLine.DPS, 1)
        val v2 = registry.pinned("CZ", ProductLine.DPS, 2)
        assertThatThrownBy {
            com.openbank.pension.domain.pack.JurisdictionPackRegistry(
                listOf(v1.copy(effectiveTo = null), v2),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("must end the day before")
        assertThatThrownBy {
            com.openbank.pension.domain.pack.JurisdictionPackRegistry(
                listOf(v1.copy(effectiveTo = v2.effectiveFrom), v2),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
