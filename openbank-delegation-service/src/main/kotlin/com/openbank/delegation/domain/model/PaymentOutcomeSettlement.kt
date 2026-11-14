// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

/**
 * ADR-0249 D3 (amended): what a payment's OUTCOME means for the money a reservation holds headroom
 * for. Deliberately not a copy of the rail's status vocabulary (rules.yaml: enum_reference_data):
 * the rail owns its statuses, and the consumer translates them into one of these three effects.
 */
enum class PaymentMoneyEffect {
    /** The money left the grantor's account (rail: SETTLED). */
    MOVED,

    /** No money left: refused, cancelled, or no payment was ever created for the reservation. */
    NEVER_MOVED,

    /**
     * The clearing system sent the payment back (rail: `SENT_TO_CLEARING -> RETURNED`). No money
     * left the grantor's control, so the headroom is given back even when an earlier,
     * rail-acceptance based confirm already consumed it.
     */
    RETURNED,
}

/**
 * One compare-and-set a settlement may perform: the row moves [from] -> [to] or nothing happens.
 *
 * [RELEASE_RETURNED_AFTER_CONFIRM] is the only transition out of CONFIRMED. It is produced by
 * [PaymentOutcomeSettlement.decide] for [PaymentMoneyEffect.RETURNED] and for nothing else, so a
 * rejected, cancelled or absent payment can never re-open a ceiling that was already spent through.
 */
enum class SpendSettlementTransition(val from: SpendReservationState, val to: SpendReservationState) {
    CONFIRM(SpendReservationState.RESERVED, SpendReservationState.CONFIRMED),
    RELEASE(SpendReservationState.RESERVED, SpendReservationState.RELEASED),
    RELEASE_RETURNED_AFTER_CONFIRM(SpendReservationState.CONFIRMED, SpendReservationState.RELEASED),
}

/** What a payment outcome means for a reservation currently in some state. */
sealed interface SettlementDecision {
    data class Apply(val transition: SpendSettlementTransition) : SettlementDecision

    /** The reservation already carries this outcome (redelivery, or the edge settled it first). */
    data object AlreadySettled : SettlementDecision

    /** The reservation is in the OTHER terminal state; the outcome is stale or contradictory. */
    data object Conflict : SettlementDecision
}

object PaymentOutcomeSettlement {
    fun decide(effect: PaymentMoneyEffect, current: SpendReservationState): SettlementDecision = when (effect) {
        PaymentMoneyEffect.MOVED -> when (current) {
            SpendReservationState.RESERVED -> SettlementDecision.Apply(SpendSettlementTransition.CONFIRM)
            SpendReservationState.CONFIRMED -> SettlementDecision.AlreadySettled
            SpendReservationState.RELEASED -> SettlementDecision.Conflict
        }

        PaymentMoneyEffect.NEVER_MOVED -> when (current) {
            SpendReservationState.RESERVED -> SettlementDecision.Apply(SpendSettlementTransition.RELEASE)
            SpendReservationState.RELEASED -> SettlementDecision.AlreadySettled
            SpendReservationState.CONFIRMED -> SettlementDecision.Conflict
        }

        PaymentMoneyEffect.RETURNED -> when (current) {
            SpendReservationState.RESERVED -> SettlementDecision.Apply(SpendSettlementTransition.RELEASE)
            SpendReservationState.CONFIRMED ->
                SettlementDecision.Apply(SpendSettlementTransition.RELEASE_RETURNED_AFTER_CONFIRM)
            SpendReservationState.RELEASED -> SettlementDecision.AlreadySettled
        }
    }
}
