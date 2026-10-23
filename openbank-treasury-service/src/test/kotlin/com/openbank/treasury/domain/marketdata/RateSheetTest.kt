// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.marketdata

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class RateSheetTest {
    private val at = Instant.parse("2026-10-19T09:00:00Z")
    private val bo = Actor("bert.backoffice", ActorType.HUMAN)
    private val approver = Actor("olga.approver", ActorType.HUMAN)
    private val rates = listOf(RateSheetRate("1M", BigDecimal("3.50")), RateSheetRate("3M", BigDecimal("3.60")))

    private fun sheet() = RateSheet.propose("Deposits", LocalDate.parse("2026-10-20"), rates, bo, at)

    @Test
    fun `a different human approves - never the proposer, an AI agent or a machine`() {
        assertThatThrownBy { sheet().approve(bo, at) }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy { sheet().reject(bo, "no", at) }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy { sheet().approve(Actor("agent:treasury-drafter", ActorType.AI_AGENT), at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy { sheet().approve(Actor("service-account-x", ActorType.SERVICE), at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        val active = sheet().approve(approver, at)
        assertThat(active.status).isEqualTo(RateSheetStatus.ACTIVE)
        assertThat(active.decidedBy).isEqualTo(approver)
        assertThatThrownBy { active.reject(approver, "late", at) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `only a person proposes, and the sheet and its rates are bounded`() {
        assertThatThrownBy { RateSheet.propose("X", LocalDate.EPOCH, rates, Actor("agent:a", ActorType.AI_AGENT), at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy {
            RateSheet.propose(" ", LocalDate.EPOCH, rates, bo, at)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            RateSheet.propose("X", LocalDate.EPOCH, emptyList(), bo, at)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            RateSheet.propose("X", LocalDate.EPOCH, rates + rates, bo, at)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RateSheetRate("1M", BigDecimal("101")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            RateSheetRate("1M", BigDecimal("1.1234567"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { sheet().reject(approver, "", at) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
