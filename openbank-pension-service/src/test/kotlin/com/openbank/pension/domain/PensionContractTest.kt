// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain

import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PensionContractTest {

    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val today = LocalDate.parse("2026-10-09")

    private fun draft(beneficiaries: List<Beneficiary> = emptyList()) = PensionContract.draft(
        participantPartyId = UUID.randomUUID(),
        productLine = ProductLine.DPS,
        jurisdiction = "XX",
        packVersion = 1,
        providerEntityId = UUID.randomUUID(),
        providerType = ProviderType.PENSION_COMPANY,
        participantBirthDate = LocalDate.parse("1990-01-01"),
        schedule = ContributionSchedule(BigDecimal("1700"), "CZK", ContributionFrequency.MONTHLY),
        initialStrategy = "BALANCED",
        beneficiaries = beneficiaries,
        today = today,
        now = now,
    )

    private fun active() = draft().submit(now).activate(today, now)

    @Test
    fun `full happy path walks the lifecycle`() {
        val c = active()
        assertThat(c.status).isEqualTo(ContractStatus.ACTIVE)
        assertThat(c.startDate).isEqualTo(today)
        val suspended = c.suspendContributions(now)
        assertThat(suspended.status).isEqualTo(ContractStatus.SUSPENDED)
        val resumed = suspended.resumeContributions(now)
        assertThat(resumed.status).isEqualTo(ContractStatus.ACTIVE)
        assertThat(resumed.requestTermination(now).status).isEqualTo(ContractStatus.TERMINATING)
    }

    @Test
    fun `a draft cannot be activated without being submitted`() {
        assertThatThrownBy { draft().activate(today, now) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("DRAFT -> ACTIVE")
    }

    @Test
    fun `an active contract cannot resume and a draft cannot be suspended`() {
        assertThatThrownBy { active().resumeContributions(now) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { draft().suspendContributions(now) }.isInstanceOf(IllegalStateException::class.java)
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus::class, names = ["PAID_OUT", "TRANSFERRED_OUT", "CLOSED"])
    fun `terminal states have no outgoing edge`(terminal: ContractStatus) {
        assertThat(terminal.terminal).isTrue()
        ContractStatus.entries.forEach { assertThat(terminal.canMoveTo(it)).isFalse() }
    }

    @Test
    fun `strategy changes keep the history and the newest election is current`() {
        val later = today.plusMonths(1)
        val c = active().electStrategy("DYNAMIC", later, now.plusSeconds(1))
        assertThat(c.strategyHistory.map { it.strategyCode }).containsExactly("BALANCED", "DYNAMIC")
        assertThat(c.currentStrategy.strategyCode).isEqualTo("DYNAMIC")
    }

    @Test
    fun `strategy in force excludes future elections and starts inclusively on the effective date`() {
        val effective = today.plusMonths(1)
        val contract = active().electStrategy("DYNAMIC", effective, now.plusSeconds(1))
        assertThat(contract.strategyOn(today)?.strategyCode).isEqualTo("BALANCED")
        assertThat(contract.strategyOn(effective.minusDays(1))?.strategyCode).isEqualTo("BALANCED")
        assertThat(contract.strategyOn(effective)?.strategyCode).isEqualTo("DYNAMIC")
        assertThat(contract.strategyOn(today.minusDays(1))).isNull()
    }

    @Test
    fun `same effective date uses the last election without depending on history order`() {
        val contract = active()
            .electStrategy("DYNAMIC", today, now.plusSeconds(1))
            .electStrategy("CONSERVATIVE", today, now.plusSeconds(2))
        assertThat(contract.copy(strategyHistory = contract.strategyHistory.reversed()).strategyOn(today)?.strategyCode)
            .isEqualTo("CONSERVATIVE")
    }

    @Test
    fun `strategy cannot change once termination was requested`() {
        val terminating = active().requestTermination(now)
        assertThatThrownBy { terminating.electStrategy("DYNAMIC", today, now) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `beneficiary shares must total one hundred`() {
        assertThatThrownBy { draft(listOf(Beneficiary("A", sharePercent = BigDecimal("60")))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val ok = draft(
            listOf(
                Beneficiary("A", sharePercent = BigDecimal("60")),
                Beneficiary("B", sharePercent = BigDecimal("40")),
            ),
        )
        assertThat(ok.beneficiaries).hasSize(2)
    }
}
