// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DomesticRejectReason
import java.math.BigDecimal
import java.util.UUID

/**
 * Telling a customer that money they asked to send did not go (#8432).
 *
 * notification-service has declared `TRANSACTION_FAILED` since it was written — rendered, given
 * required variables `amount`/`currency`/`reason` — and **nothing ever emitted it**. It is also the
 * only template in the whole set that covers a payment not happening: there is no SEPA- or
 * domestic-specific rejection template. Neither customer-edge nor the app exposes `rejectReason`
 * either, so today a rejected payment is silent everywhere — the money simply stays put.
 *
 * Deliberately narrow: this carries the SCHEME rejection only. See
 * [customerSafeReason] for what may be said, and the reject-activity in
 * `DomesticPaymentActivitiesImpl` for the path that deliberately stays silent.
 */
interface CustomerNotificationPort {

    /**
     * [partyId] is the account OWNER, resolved from the debtor account — not the actor who
     * submitted the payment. For a delegated payment those differ, and it is the owner whose money
     * did not move.
     *
     * [reason] must already be customer-safe; pass the output of [customerSafeReason].
     */
    suspend fun notifyPaymentFailed(partyId: UUID, amount: BigDecimal, currency: String, reason: String)
}

/**
 * The closed reason mapping, and the one control in this slice that matters.
 *
 * [DomesticRejectReason] mixes reasons a customer should simply be told — a closed beneficiary
 * account, a wrong bank code, insufficient funds — with three that name a financial-crime control
 * applied to them: [DomesticRejectReason.SANCTIONS_HIT], [DomesticRejectReason.AML_HOLD] and
 * [DomesticRejectReason.FRAUD_SUSPECTED]. Disclosing those is tipping-off, and the enum constant
 * name would go straight onto a lock screen.
 *
 * So the mapping is a closed `when` over the enum with no `else`: adding a reason forces a
 * customer-facing decision at compile time rather than defaulting one. The three sensitive reasons
 * map to a neutral sentence that names no control — and, belt and braces, the only path that can
 * currently produce them does not call this at all.
 */
fun customerSafeReason(reason: DomesticRejectReason?): String = when (reason) {
    DomesticRejectReason.INSUFFICIENT_FUNDS -> "there was not enough money in the account"
    DomesticRejectReason.BENEFICIARY_ACCOUNT_CLOSED -> "the recipient's account is closed"
    DomesticRejectReason.INVALID_ACCOUNT_NUMBER -> "the recipient's account number was not accepted"
    DomesticRejectReason.INVALID_BANK_CODE -> "the recipient's bank code was not accepted"
    DomesticRejectReason.AMOUNT_LIMIT_EXCEEDED -> "the amount is above the limit for this payment"
    DomesticRejectReason.TECHNICAL_ERROR -> "of a technical problem"
    // Names no control, for the same customer-facing reason in all three cases.
    DomesticRejectReason.SANCTIONS_HIT,
    DomesticRejectReason.AML_HOLD,
    DomesticRejectReason.FRAUD_SUSPECTED,
    null,
    -> "we could not complete it"
}
