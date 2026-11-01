// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.domestic.domain.error.DomesticSchemeRules
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.domain.money.Money
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.util.UUID

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): one step of the Czech domestic
 * clearing path, modelled directly against the **real `domestic-payment` `DomesticPayment`**
 * status machine (`transitionTo`/`canTransitionTo`, including its REJECTED-requires-a-reason
 * rule) and the **real scheme rule** [DomesticSchemeRules.requireSchemeCurrency] (domestic
 * transfers are CZK-only) — not a re-model of either, the same way [SepaSettlementScenario]
 * builds on the real `SepaPayment`/`Settlement` aggregates (ADR-0100). Both domain packages
 * are framework-free (ADR-0002).
 *
 * A step mirrors the production ladder: validate the domestic transfer (RECEIVED -> VALIDATED),
 * hand it to clearing (VALIDATED -> SENT_TO_CLEARING, stamping `schemeDispatchedAt` BEFORE the
 * outbound hop exactly as #4218 requires), debit + credit the settlement, and complete both
 * aggregates together (SENT_TO_CLEARING -> SETTLED, CREDITED -> BOOKED). An injected write
 * fault at the scheme-dispatch step rejects the payment (the dispatch never committed, so
 * `schemeDispatchedAt` stays null) and marks the settlement REJECTED while still PENDING —
 * nothing to unwind. An injected post-commit conflict returns the payment (SENT_TO_CLEARING ->
 * RETURNED, the state machine's compensation transition) and reverses the credited settlement,
 * so every step reaches a terminal state on both aggregates.
 */
object DomesticClearingScenario {

    private const val MAX_MINOR_UNITS = 500_000L // up to 5000.00 in a 2-dp currency
    private const val BANK_CODE = "0800"

    fun step(world: World) {
        var leg = openLeg(world)

        // 1. Validate the domestic transfer (RECEIVED -> VALIDATED on the real state machine).
        leg = leg.advancePayment(world, DomesticPaymentStatus.VALIDATED)

        // 2. Hand the payment to clearing. An injected write fault here means the dispatch never
        //    committed: reject the payment (with the machine-required reason, before any
        //    schemeDispatchedAt stamp) and mark the settlement REJECTED while still PENDING.
        val dispatched = dispatchToScheme(world, leg)
        if (dispatched == null) return
        leg = dispatched

        // 3. Debit the payer's settlement leg.
        val debited = debitSettlement(world, leg)
        if (debited == null) return
        leg = debited

        // 4. A post-commit conflict forces the settlement to reverse the already-booked debit
        //    (compensation), and the domestic payment takes the machine's RETURNED branch.
        if (world.context.faults.shouldConflict()) {
            leg = leg.advanceSettlement(world, SettlementStatus.CREDITED)
            leg = leg.advancePayment(world, DomesticPaymentStatus.RETURNED)
            leg.advanceSettlement(world, SettlementStatus.CREDITED_REVERSED)
            world.audit.append("domestic ${leg.payment.id} returned: post-commit conflict")
            return
        }

        // 5. Happy path: credit + book the settlement and settle the payment together.
        leg = leg.advanceSettlement(world, SettlementStatus.CREDITED)
        leg = leg.advanceSettlement(world, SettlementStatus.BOOKED)
        leg.advancePayment(world, DomesticPaymentStatus.SETTLED)
    }

    private fun dispatchToScheme(world: World, leg: DomesticLeg): DomesticLeg? = try {
        if (world.context.faults.shouldFailWrite()) {
            throw SimulatedWriteFailure("scheme dispatch failed for ${leg.payment.id}")
        }
        // #4218: the dispatch stamp is written BEFORE the outbound call, so it survives a
        // failure of anything that follows it.
        leg.storePayment(world, leg.payment.copy(schemeDispatchedAt = world.context.clock.instant()))
            .advancePayment(world, DomesticPaymentStatus.SENT_TO_CLEARING)
    } catch (e: SimulatedWriteFailure) {
        val rejected = leg.advancePayment(
            world,
            DomesticPaymentStatus.REJECTED,
            reason = DomesticRejectReason.TECHNICAL_ERROR,
            detail = e.message,
        )
        rejected.advanceSettlement(world, SettlementStatus.REJECTED)
        world.audit.append("domestic ${leg.payment.id} rejected: ${e.message}")
        null
    }

    private fun debitSettlement(world: World, leg: DomesticLeg): DomesticLeg? = try {
        if (world.context.faults.shouldFailWrite()) {
            throw SimulatedWriteFailure("settlement debit failed for ${leg.settlement.id}")
        }
        leg.advanceSettlement(world, SettlementStatus.DEBITED)
    } catch (e: SimulatedWriteFailure) {
        // The payment already carries schemeDispatchedAt (the dispatch committed); the failure
        // is on the money movement, and the settlement never left PENDING, so it is REJECTED
        // with nothing to unwind.
        val rejected = leg.advancePayment(
            world,
            DomesticPaymentStatus.REJECTED,
            reason = DomesticRejectReason.TECHNICAL_ERROR,
            detail = e.message,
        )
        rejected.advanceSettlement(world, SettlementStatus.REJECTED)
        world.audit.append("domestic ${leg.payment.id} rejected: ${e.message}")
        null
    }

    private fun openLeg(world: World): DomesticLeg {
        val random = world.context.random
        val accounts = world.customerAccounts
        val now = world.context.clock.instant()

        val amount = BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2)
        // Same selection shape as SwiftScenario: an inline index draw plus an equality skip.
        val debtorAccount = accounts[random.nextInt(accounts.size)]
        val creditorAccount = accounts.first { it != debtorAccount }

        // Czech domestic transfers are CZK-only. The amount is built in the world's currency and
        // the REAL scheme rule is applied before the payment enters the world, so a regression
        // in `DomesticSchemeRules` (or a scenario that silently switched currencies) fails here.
        val payment = DomesticPayment(
            id = random.nextUuid(),
            idempotencyKey = "domestic-${random.nextUuid()}",
            status = DomesticPaymentStatus.RECEIVED,
            debtorAccountId = debtorAccount,
            debtorAccountNumber = accountNumber(debtorAccount),
            debtorBankCode = BANK_CODE,
            debtorName = "Simulated Domestic Debtor",
            creditorAccountNumber = accountNumber(creditorAccount),
            creditorBankCode = BANK_CODE,
            creditorName = "Simulated Domestic Creditor",
            amount = Money.of(amount, DomesticSchemeRules.SCHEME_CURRENCY.code),
            variableSymbol = null,
            specificSymbol = null,
            constantSymbol = null,
            messageForPayee = null,
            priority = DomesticPaymentPriority.STANDARD,
            transferScope = DomesticTransferScope.INTERNAL_CLIENT,
            technicalAccountCode = null,
            statementLabel = null,
            endToEndId = "e2e-${random.nextUuid()}",
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            settledAt = null,
            createdAt = now,
            updatedAt = now,
        )
        DomesticSchemeRules.requireSchemeCurrency(payment.amount)
        world.domesticPayments.add(payment)

        val settlement = Settlement(
            id = random.nextUuid(),
            payerAccountId = debtorAccount,
            payeeAccountId = creditorAccount,
            amount = amount,
            currency = DomesticSchemeRules.SCHEME_CURRENCY.code,
            status = SettlementStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
        world.settlements.add(settlement)

        return DomesticLeg(payment, settlement, world.domesticPayments.lastIndex, world.settlements.lastIndex)
    }

    private const val ACCOUNT_NUMBER_LENGTH = 10

    private fun accountNumber(accountId: UUID): String =
        accountId.toString().replace("-", "").take(ACCOUNT_NUMBER_LENGTH)

    /** The pair of real domain aggregates driven together by one scenario step. */
    private data class DomesticLeg(
        val payment: DomesticPayment,
        val settlement: Settlement,
        val paymentIndex: Int,
        val settlementIndex: Int,
    ) {
        fun advancePayment(
            world: World,
            to: DomesticPaymentStatus,
            reason: DomesticRejectReason? = null,
            detail: String? = null,
        ): DomesticLeg {
            // transitionTo is the production state machine: an illegal hop (e.g. SETTLED from
            // SENT_TO_CLEARING is legal, but a second SETTLED is not) throws here, and REJECTED
            // without a reason is refused by the machine itself.
            val next = payment.transitionTo(to, reason = reason, detail = detail, clock = world.context.clock)
            world.domesticPayments[paymentIndex] = next
            world.audit.append("domestic ${next.id} -> ${next.status}")
            return copy(payment = next)
        }

        fun advanceSettlement(world: World, to: SettlementStatus): DomesticLeg {
            val next: Settlement = settlement.copy(status = to, updatedAt = world.context.clock.instant())
            world.settlements[settlementIndex] = next
            world.audit.append("settlement ${next.id} -> ${next.status}")
            return copy(settlement = next)
        }

        fun storePayment(world: World, next: DomesticPayment): DomesticLeg {
            world.domesticPayments[paymentIndex] = next
            return copy(payment = next)
        }
    }
}
