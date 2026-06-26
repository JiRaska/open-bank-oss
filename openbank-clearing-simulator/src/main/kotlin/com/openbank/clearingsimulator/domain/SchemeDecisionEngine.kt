// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearingsimulator.domain

import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.ReceivedCreditTransfer
import java.math.BigDecimal

/**
 * An ISO 20022 `ExternalStatusReason1Code` the simulator can return on a reject, with the gloss
 * a real CSM would carry. `FF01` is reserved for a message that fails XSD validation.
 */
enum class RejectReason(val code: String, val description: String) {
    AC04("AC04", "closed account"),
    AM05("AM05", "duplication"),
    RR04("RR04", "regulatory reason"),
    FF01("FF01", "invalid pacs.008 (fails XSD validation)"),
}

/** The simulator's verdict on a credit transfer: a settlement ack or a reject with a reason. */
data class SchemeDecision(val status: PaymentStatus, val reason: RejectReason?) {
    val settled: Boolean get() = status == PaymentStatus.ACSC

    companion object {
        val SETTLED = SchemeDecision(PaymentStatus.ACSC, null)

        fun rejected(reason: RejectReason) = SchemeDecision(PaymentStatus.RJCT, reason)
    }
}

/**
 * Decides whether an inbound credit transfer settles or rejects — deterministically (ADR-0100).
 *
 * A real clearing system's accept/reject depends on the receiving bank's account state, which the
 * simulator does not have. Instead it keys the verdict off the transfer's **minor-unit remainder**
 * (the last two digits of the amount in minor units), so every demo and test is reproducible and
 * each reject path can be triggered on demand:
 *
 * | remainder | verdict                         |
 * |-----------|---------------------------------|
 * | 01        | RJCT [RejectReason.AC04]        |
 * | 02        | RJCT [RejectReason.AM05]        |
 * | 04        | RJCT [RejectReason.RR04]        |
 * | otherwise | ACSC (settled)                  |
 *
 * This mirrors the "magic amount" trigger convention real scheme test sandboxes use. The mapping is
 * the simulator's only behavioural knob; swapping it for a real gateway (ADR-0104 swap-point) drops
 * this engine entirely.
 */
class SchemeDecisionEngine {
    fun decide(transfer: ReceivedCreditTransfer): SchemeDecision = when (minorUnitRemainder(transfer.amount)) {
        TRIGGER_CLOSED_ACCOUNT -> SchemeDecision.rejected(RejectReason.AC04)
        TRIGGER_DUPLICATION -> SchemeDecision.rejected(RejectReason.AM05)
        TRIGGER_REGULATORY -> SchemeDecision.rejected(RejectReason.RR04)
        else -> SchemeDecision.SETTLED
    }

    private fun minorUnitRemainder(amount: BigDecimal): Int =
        amount.movePointRight(MINOR_UNIT_SCALE).toBigInteger().mod(HUNDRED).toInt()

    private companion object {
        const val MINOR_UNIT_SCALE = 2
        val HUNDRED = java.math.BigInteger.valueOf(100)

        // Minor-unit remainder values that deterministically trigger a reject (ADR-0100).
        const val TRIGGER_CLOSED_ACCOUNT = 1
        const val TRIGGER_DUPLICATION = 2
        const val TRIGGER_REGULATORY = 4
    }
}
