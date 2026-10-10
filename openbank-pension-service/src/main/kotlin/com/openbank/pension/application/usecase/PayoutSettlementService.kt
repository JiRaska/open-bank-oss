// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.exit.InstructionStatus
import com.openbank.pension.application.exit.PaymentInstruction
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Settlement view of `pension_payment_instructions` (#12378). Separate from the S5
 * PaymentInstructionRepository on purpose: it is called from a Kafka consumer, which has no
 * Hibernate session, so its implementation runs on the plain reactive pool.
 */
interface PayoutSettlementRepository {
    /** The instruction a rail payment belongs to, by the reference PayoutPaymentPort.pay returned. */
    suspend fun findByPaymentRef(paymentRef: String): PaymentInstruction?

    /** Records the rail's outcome; false when the row's status was not in [from] (replay / out of order). */
    suspend fun markSettlement(
        paymentRef: String,
        from: Set<InstructionStatus>,
        to: InstructionStatus,
        occurredAt: Instant,
        settledAt: Instant?,
    ): Boolean
}

/** What domestic-payment says happened to a payment; anything in between is not an outcome yet. */
enum class RailSettlement { SETTLED, REJECTED }

/** What [PayoutSettlementService.record] did with one rail status change. */
enum class SettlementOutcome { SETTLED, REJECTED, NOT_OURS, UNCHANGED }

/**
 * Writes a payout payment's settlement back onto the instruction the payout workflow sent
 * (#12378). `pay` returning a reference means the rail ACCEPTED the order, not that money arrived;
 * before this, a rejected or returned payout read SENT forever and nothing could tell it from a
 * settled one (the PushResult "accepted is not delivered" lesson, #4348).
 *
 * Transitions: SENT → SETTLED | REJECTED, and SETTLED → REJECTED (a return after settlement takes
 * the money back). A replayed event changes nothing ([SettlementOutcome.UNCHANGED]); a payment no
 * pension instruction references is [SettlementOutcome.NOT_OURS] — the topic carries every domestic
 * payment of the bank. Every REJECTED is logged at ERROR and counted with its own outcome label, so
 * "payouts rejected" is an alert on a counter, not a search through logs.
 */
class PayoutSettlementService(
    private val instructions: PayoutSettlementRepository,
    private val count: (SettlementOutcome) -> Unit = {},
) {
    private val log = Logger.getLogger(PayoutSettlementService::class.java)

    suspend fun record(
        paymentRef: String,
        settlement: RailSettlement,
        occurredAt: Instant,
        settledAt: Instant? = null,
    ): SettlementOutcome {
        val instruction = instructions.findByPaymentRef(paymentRef) ?: return SettlementOutcome.NOT_OURS
        val (from, to) = when (settlement) {
            RailSettlement.SETTLED -> setOf(InstructionStatus.SENT) to InstructionStatus.SETTLED
            RailSettlement.REJECTED ->
                setOf(InstructionStatus.SENT, InstructionStatus.SETTLED) to InstructionStatus.REJECTED
        }
        // The source payment's persisted transition time can precede status-event creation.
        // A legacy SETTLED event without it still records SETTLED/null so reporting fails closed.
        val changed = instructions.markSettlement(paymentRef, from, to, occurredAt, settledAt)
        val outcome = when {
            !changed -> SettlementOutcome.UNCHANGED
            to == InstructionStatus.SETTLED -> SettlementOutcome.SETTLED
            else -> SettlementOutcome.REJECTED
        }
        if (outcome == SettlementOutcome.REJECTED) {
            log.errorf(
                "payout payment %s (instruction %s, contract %s) was REJECTED by the rail; the participant was not paid",
                paymentRef,
                instruction.idempotencyKey,
                instruction.contractId,
            )
        }
        count(outcome)
        return outcome
    }
}
