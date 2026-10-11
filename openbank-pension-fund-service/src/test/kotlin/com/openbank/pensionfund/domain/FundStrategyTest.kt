// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain

import com.openbank.pensionfund.domain.model.AllocationTarget
import com.openbank.pensionfund.domain.model.FourEyesViolationException
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.FundStrategy
import com.openbank.pensionfund.domain.model.GlidePathStep
import com.openbank.pensionfund.domain.model.StrategyChange
import com.openbank.pensionfund.domain.model.StrategyChangeStatus
import com.openbank.pensionfund.domain.model.StrategyStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FundStrategyTest {

    private val equity = UUID.randomUUID()
    private val bonds = UUID.randomUUID()
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val today = LocalDate.parse("2026-10-09")

    private fun target(fund: UUID, w: String) = AllocationTarget(
        fund,
        BigDecimal(w),
        BigDecimal(w) - BigDecimal("0.05").min(BigDecimal(w)),
        (BigDecimal(w) + BigDecimal("0.05")).min(BigDecimal.ONE),
    )

    private fun mix(e: String, b: String) = listOf(target(equity, e), target(bonds, b))

    private val lifecycle = FundStrategy(
        id = UUID.randomUUID(),
        name = "Lifecycle 2060",
        allocations = mix("0.6", "0.4"),
        glidePath = listOf(
            GlidePathStep(20, mix("0.9", "0.1")),
            GlidePathStep(10, mix("0.6", "0.4")),
            GlidePathStep(0, mix("0.2", "0.8")),
        ),
        status = StrategyStatus.ACTIVE,
        version = 1,
        createdAt = now,
        updatedAt = now,
    )

    private fun weightOf(fund: UUID, years: Int) = lifecycle.allocationFor(years).single { it.fundId == fund }.weight

    @Test
    fun `glide path de-risks as retirement approaches`() {
        assertThat(weightOf(equity, 35)).isEqualByComparingTo("0.9")
        assertThat(weightOf(equity, 20)).isEqualByComparingTo("0.9")
        assertThat(weightOf(equity, 19)).isEqualByComparingTo("0.6")
        assertThat(weightOf(equity, 10)).isEqualByComparingTo("0.6")
        assertThat(weightOf(equity, 9)).isEqualByComparingTo("0.2")
        assertThat(weightOf(equity, 0)).isEqualByComparingTo("0.2")
        assertThat(weightOf(equity, -3)).isEqualByComparingTo("0.2")
    }

    @Test
    fun `a glide path must cover zero years to retirement`() {
        assertThatThrownBy { lifecycle.copy(glidePath = listOf(GlidePathStep(10, mix("0.5", "0.5")))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `weights must sum to one and sit inside their bands`() {
        assertThatThrownBy {
            lifecycle.copy(allocations = mix("0.6", "0.3"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AllocationTarget(equity, BigDecimal("0.5"), BigDecimal("0.55"), BigDecimal("0.6")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `drift outside the band flags the fund for rebalancing`() {
        val drifted = lifecycle.fundsOutsideBand(mapOf(equity to BigDecimal("0.97"), bonds to BigDecimal("0.03")), 30)
        assertThat(drifted).containsExactlyInAnyOrder(equity, bonds)
        assertThat(
            lifecycle.fundsOutsideBand(mapOf(equity to BigDecimal("0.88"), bonds to BigDecimal("0.12")), 30),
        ).isEmpty()
    }

    private fun submit(effective: LocalDate = today.plusDays(30)) = StrategyChange.submit(
        id = UUID.randomUUID(),
        strategyId = lifecycle.id,
        proposedAllocations = mix("0.5", "0.5"),
        proposedGlidePath = emptyList(),
        reason = "de-risk",
        effectiveDate = effective,
        submittedBy = "maker",
        now = now,
        today = today,
        minimumNoticeDays = 30,
    )

    @Test
    fun `a change needs a second person and the notice period before it applies`() {
        assertThatThrownBy { submit(today.plusDays(29)) }.isInstanceOf(IllegalArgumentException::class.java)
        val change = submit()
        assertThatThrownBy {
            change.approve("maker", now, today, 30)
        }.isInstanceOf(FourEyesViolationException::class.java)
        val approved = change.approve("checker", now, today, 30)
        assertThat(approved.status).isEqualTo(StrategyChangeStatus.APPROVED)
        assertThat(approved.participantNotificationDate).isEqualTo(today)
        assertThatThrownBy {
            approved.markApplied(now, today.plusDays(29))
        }.isInstanceOf(IllegalStateException::class.java)

        val applied = approved.markApplied(now, today.plusDays(30))
        val updated = lifecycle.apply(applied, now)
        assertThat(updated.version).isEqualTo(2)
        assertThat(updated.isLifecycle).isFalse()
        assertThat(updated.allocationFor(40).single { it.fundId == equity }.weight).isEqualByComparingTo("0.5")
    }

    @Test
    fun `approval that comes too late to leave the notice period is refused`() {
        val change = submit()
        assertThatThrownBy {
            change.approve("checker", now, today.plusDays(1), 30)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `fund identifiers are validated`() {
        val fund = Fund(
            id = UUID.randomUUID(), name = "Conservative", isin = "CZ0008474053", lei = "315700ABCDEF12345678",
            depositaryReference = "DEP-1", custodyAccountReference = "CUST-1", currency = "CZK", riskClass = 1,
            mandatoryConservative = true, managementFeeRate = BigDecimal("0.004"), launchNavPerUnit = BigDecimal.ONE,
            status = FundStatus.ACTIVE, createdAt = now, updatedAt = now,
        )
        assertThatThrownBy { fund.copy(isin = "CZ123") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { fund.copy(lei = "short") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { fund.copy(riskClass = 8) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { fund.copy(depositaryReference = " ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fund.close(now).status).isEqualTo(FundStatus.CLOSED)
    }
}
