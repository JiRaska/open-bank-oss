// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.delegation.domain.model.PaymentMoneyEffect.MOVED
import com.openbank.delegation.domain.model.PaymentMoneyEffect.NEVER_MOVED
import com.openbank.delegation.domain.model.PaymentMoneyEffect.RETURNED
import com.openbank.delegation.domain.model.SettlementDecision.AlreadySettled
import com.openbank.delegation.domain.model.SettlementDecision.Apply
import com.openbank.delegation.domain.model.SettlementDecision.Conflict
import com.openbank.delegation.domain.model.SpendReservationState.CONFIRMED
import com.openbank.delegation.domain.model.SpendReservationState.RELEASED
import com.openbank.delegation.domain.model.SpendReservationState.RESERVED
import com.openbank.delegation.domain.model.SpendSettlementTransition.CONFIRM
import com.openbank.delegation.domain.model.SpendSettlementTransition.RELEASE
import com.openbank.delegation.domain.model.SpendSettlementTransition.RELEASE_RETURNED_AFTER_CONFIRM
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaymentOutcomeSettlementTest {

    /** The whole table, 3 effects x 3 states, so a new cell cannot appear unreviewed. */
    private val expected: Map<Pair<PaymentMoneyEffect, SpendReservationState>, SettlementDecision> = mapOf(
        (MOVED to RESERVED) to Apply(CONFIRM),
        (MOVED to CONFIRMED) to AlreadySettled,
        (MOVED to RELEASED) to Conflict,
        (NEVER_MOVED to RESERVED) to Apply(RELEASE),
        (NEVER_MOVED to CONFIRMED) to Conflict,
        (NEVER_MOVED to RELEASED) to AlreadySettled,
        (RETURNED to RESERVED) to Apply(RELEASE),
        (RETURNED to CONFIRMED) to Apply(RELEASE_RETURNED_AFTER_CONFIRM),
        (RETURNED to RELEASED) to AlreadySettled,
    )

    @Test
    fun `every outcome and state decides as the ADR-0249 D3 amendment says`() {
        val actual = PaymentMoneyEffect.entries.flatMap { outcome ->
            SpendReservationState.entries.map { state ->
                (outcome to state) to
                    PaymentOutcomeSettlement.decide(outcome, state)
            }
        }.toMap()
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `only RETURNED can ever leave CONFIRMED`() {
        val leavingConfirmed = PaymentMoneyEffect.entries.filter {
            val decision = PaymentOutcomeSettlement.decide(it, CONFIRMED)
            decision is Apply && decision.transition.from == CONFIRMED
        }
        assertThat(leavingConfirmed).containsExactly(RETURNED)
    }

    @Test
    fun `every applied transition starts in the state it was decided from`() {
        PaymentMoneyEffect.entries.forEach { outcome ->
            SpendReservationState.entries.forEach { state ->
                val decision = PaymentOutcomeSettlement.decide(outcome, state)
                if (decision is Apply) assertThat(decision.transition.from).isEqualTo(state)
            }
        }
    }
}
