// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.util.UUID

/**
 * Issue #267 (ADR-0100 full-service adoption): one step of the SEPA cross-account settlement
 * path, modelled directly against the **real `sepa-payment` `SepaPayment`** status machine
 * (`transitionTo`/`canTransitionTo`) and the **real `settlement-service` `Settlement`** status
 * enum — not a re-model of either. Both domain packages are framework-free (ADR-0002), so this
 * exercises the exact production transition rules under the deterministic scheduler + fault
 * injector, the same way [PaymentScenario] already does for `ledger`/`balance`.
 *
 * A step: submit a SEPA credit transfer (RECEIVED -> VALIDATED), open its settlement (PENDING),
 * debit + credit the settlement, and complete both aggregates together (PROCESSING -> COMPLETED,
 * CREDITED -> BOOKED). An injected write fault at the settlement-debit step rejects the SEPA
 * payment and reverses the settlement, so every step reaches a terminal state on both aggregates
 * — exactly the "compensation completeness" property [MoneyPathInvariants] already asserts for
 * the internal payment saga, extended here to the SEPA + settlement pair.
 */
object SepaSettlementScenario {

    private const val MAX_MINOR_UNITS = 500_000L // up to 5000.00 in a 2-dp currency
    private const val IBAN_PREFIX_LENGTH = 24
    private const val CREDITOR_BIC = "OPENBACZPXXX"

    fun step(world: World) {
        var leg = openLeg(world)

        // 1. Validate the SEPA credit transfer.
        leg = leg.advancePayment(world, SepaPaymentStatus.VALIDATED)
        leg = leg.advancePayment(world, SepaPaymentStatus.PROCESSING)

        // 2. Debit the payer's settlement leg. An injected write fault here means the debit
        //    never committed: reject the payment and mark the settlement REJECTED (nothing to
        //    unwind since it never left PENDING).
        val debited = debitSettlement(world, leg)
        if (debited == null) return
        leg = debited

        // 3. Credit the payee's settlement leg. A post-commit conflict forces the settlement to
        //    reverse the already-booked debit (compensation), and the SEPA payment is RETURNED.
        if (world.context.faults.shouldConflict()) {
            leg = leg.advanceSettlement(world, SettlementStatus.CREDITED_REVERSED)
            leg.advancePayment(world, SepaPaymentStatus.RETURNED)
            world.audit.append("settlement ${leg.settlement.id} reversed: post-commit conflict")
            return
        }
        leg = leg.advanceSettlement(world, SettlementStatus.CREDITED)

        // 4. Happy path: book the settlement and complete the SEPA payment together.
        leg = leg.advanceSettlement(world, SettlementStatus.BOOKED)
        leg.advancePayment(world, SepaPaymentStatus.COMPLETED)
    }

    private fun debitSettlement(world: World, leg: SepaSettlementLeg): SepaSettlementLeg? = try {
        if (world.context.faults.shouldFailWrite()) {
            throw SimulatedWriteFailure("settlement debit failed for ${leg.settlement.id}")
        }
        leg.advanceSettlement(world, SettlementStatus.DEBITED)
    } catch (e: SimulatedWriteFailure) {
        val rejected = leg.advanceSettlement(world, SettlementStatus.REJECTED)
        rejected.advancePayment(world, SepaPaymentStatus.REJECTED, reason = SepaRejectReason.TECHNICAL_ERROR)
        world.audit.append("sepa ${leg.payment.id} rejected: ${e.message}")
        null
    }

    private fun openLeg(world: World): SepaSettlementLeg {
        val random = world.context.random
        val accounts = world.customerAccounts
        val now = world.context.clock.instant()

        val amount = BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2)
        val debtorIdx = random.nextInt(accounts.size)
        val debtorAccount = accounts[debtorIdx]
        val creditorAccountIdx = (debtorIdx + 1 + random.nextInt(accounts.size - 1)) % accounts.size
        // codeql[java/insecure-randomness]: intentional. `random` is SimulationRandom, a
        // seeded kotlin.random.Random wrapper (engine/SimulationRandom.kt) whose entire purpose
        // is deterministic, REPRODUCIBLE test-account selection (ADR-0100) -- a SecureRandom
        // here would break the seed-replay property this harness exists for. openbank-simulation
        // is a test-tooling module (no version.txt, never deployed), not production code.
        val creditorAccount = accounts[creditorAccountIdx]

        val payment = SepaPayment(
            id = random.nextUuid(),
            idempotencyKey = "sepa-${random.nextUuid()}",
            type = SepaPaymentType.SCT,
            status = SepaPaymentStatus.RECEIVED,
            debtorAccountId = debtorAccount,
            debtorIban = iban(debtorAccount),
            debtorName = "Simulated Debtor",
            creditorIban = iban(creditorAccount),
            creditorName = "Simulated Creditor",
            creditorBic = CREDITOR_BIC,
            amount = amount,
            currency = world.currency,
            remittanceInfo = null,
            endToEndId = "e2e-${random.nextUuid()}",
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        world.sepaPayments.add(payment)

        val settlement = Settlement(
            id = random.nextUuid(),
            payerAccountId = debtorAccount,
            payeeAccountId = creditorAccount,
            amount = amount,
            currency = world.currency,
            status = SettlementStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
        world.settlements.add(settlement)

        return SepaSettlementLeg(payment, settlement, world.sepaPayments.lastIndex, world.settlements.lastIndex)
    }

    private fun iban(accountId: UUID): String = "CZ0000000000$accountId".take(IBAN_PREFIX_LENGTH)

    /** The pair of real domain aggregates driven together by one scenario step. */
    private data class SepaSettlementLeg(
        val payment: SepaPayment,
        val settlement: Settlement,
        val paymentIndex: Int,
        val settlementIndex: Int,
    ) {
        fun advancePayment(world: World, to: SepaPaymentStatus, reason: SepaRejectReason? = null): SepaSettlementLeg {
            val next = payment.transitionTo(to, reason = reason, clock = world.context.clock)
            world.sepaPayments[paymentIndex] = next
            world.audit.append("sepa ${next.id} -> ${next.status}")
            return copy(payment = next)
        }

        fun advanceSettlement(world: World, to: SettlementStatus): SepaSettlementLeg {
            val next: Settlement = settlement.copy(status = to, updatedAt = world.context.clock.instant())
            world.settlements[settlementIndex] = next
            world.audit.append("settlement ${next.id} -> ${next.status}")
            return copy(settlement = next)
        }
    }
}
