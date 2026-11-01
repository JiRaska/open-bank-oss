// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.libs.domain.money.Money
import com.openbank.sepainstant.domain.error.SctInstSchemeRules
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): one step of the SEPA Instant
 * settlement path, modelled directly against the **real `sepa-instant` `SctInstPayment`**
 * aggregate and the **real scheme rule** that guards it — [SctInstSchemeRules.requireSchemeCurrency]
 * (SCT Inst is euro-only, EPC SCT Inst rulebook) — not a re-model of either, the same way
 * [SepaSettlementScenario] already builds on the real `SepaPayment`/`Settlement` aggregates
 * (ADR-0100). Both domain packages are framework-free (ADR-0002).
 *
 * A step mirrors the production ladder in `SctInstPaymentService`: submission holds the payment
 * PENDING while screening runs, the CLEAR decision releases it to PROCESSING and arms
 * `executionTimeoutAt`, settlement completes it PROCESSING -> SETTLED, and only a SETTLED payment
 * may be recalled (-> RECALLED) — the same precondition the service's `recall` enforces with a
 * 400. An injected write fault at the scheme-submission step rejects the payment instead
 * (the production `rejectScheme` shape: REJECTED with a reject reason, before anything moved,
 * so the settlement is REJECTED while still PENDING — nothing to unwind). An injected
 * post-settlement conflict recalls the SETTLED payment and reverses the booked settlement, so
 * every step reaches a terminal state on both aggregates — the instant-payment analogue of the
 * "compensation completeness" property [com.openbank.simulation.invariants.MoneyPathInvariants]
 * already asserts for the SEPA pair.
 */
object SctInstSettlementScenario {

    private const val MAX_MINOR_UNITS = 500_000L // up to 5000.00 in a 2-dp currency
    private const val IBAN_PREFIX_LENGTH = 24
    private const val CREDITOR_BIC = "OPENBACZPXXX"

    /** The scheme execution window the production `proceed` step arms (now + timeout). */
    private const val EXECUTION_TIMEOUT_SECONDS = 60L
    private const val SCHEME_REJECT_REASON = "SIMULATED_SCHEME_REJECTION"

    fun step(world: World) {
        var leg = openLeg(world)

        // 1. Screening decision. An injected write fault here means the scheme submission never
        //    committed: reject the payment before anything moved and mark the settlement
        //    REJECTED while it is still PENDING (nothing to unwind).
        val submitted = submitToScheme(world, leg)
        if (submitted == null) return
        leg = submitted

        // 2. Settle the instant payment: debit + credit the settlement legs, then complete the
        //    payment PROCESSING -> SETTLED exactly as the production `complete` does.
        leg = leg.advanceSettlement(world, SettlementStatus.DEBITED)
        leg = leg.advanceSettlement(world, SettlementStatus.CREDITED)
        leg = leg.advanceSettlement(world, SettlementStatus.BOOKED)
        leg = leg.complete(world)

        // 3. A post-settlement conflict means the scheme returned the instant payment after the
        //    funds were booked: only a SETTLED payment may be recalled (the production recall
        //    guard), and the booked settlement reverses.
        if (world.context.faults.shouldConflict()) {
            leg = leg.recall(world, reason = "SIMULATED_POST_SETTLEMENT_CONFLICT")
            leg.advanceSettlement(world, SettlementStatus.REVERSED)
            world.audit.append("sct-inst ${leg.payment.id} recalled: post-settlement conflict")
            return
        }
        world.audit.append("sct-inst ${leg.payment.id} settled: ${leg.payment.amount}")
    }

    private fun submitToScheme(world: World, leg: SctInstLeg): SctInstLeg? = try {
        if (world.context.faults.shouldFailWrite()) {
            throw SimulatedWriteFailure("scheme submission failed for ${leg.payment.id}")
        }
        leg.proceed(world)
    } catch (e: SimulatedWriteFailure) {
        val rejected = leg.advancePayment(world, SctInstStatus.REJECTED)
            .storePayment(
                world,
                leg.payment.copy(
                    status = SctInstStatus.REJECTED,
                    rejectReason = SCHEME_REJECT_REASON,
                    rejectDetail = e.message,
                ),
            )
        rejected.advanceSettlement(world, SettlementStatus.REJECTED)
        world.audit.append("sct-inst ${leg.payment.id} rejected: ${e.message}")
        null
    }

    private fun openLeg(world: World): SctInstLeg {
        val random = world.context.random
        val accounts = world.customerAccounts
        val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)

        val amount = BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2)
        // Same selection shape as SwiftScenario: an inline index draw plus an equality skip.
        // (The derived-index arithmetic in SepaSettlementScenario trips CodeQL's
        // java/insecure-randomness on new files; SimulationRandom stays the seeded, replayable
        // source either way, so the simpler form loses nothing.)
        val debtorAccount = accounts[random.nextInt(accounts.size)]
        val creditorAccount = accounts.first { it != debtorAccount }

        // SCT Inst is euro-only (EPC SCT Inst rulebook). The amount is built in EUR and the
        // REAL scheme rule is applied before the payment enters the world, so a regression in
        // `SctInstSchemeRules` (or a scenario that silently built CZK amounts) fails here.
        val payment = SctInstPayment(
            id = 0,
            paymentId = random.nextUuid(),
            idempotencyKey = "sct-inst-${random.nextUuid()}",
            status = SctInstStatus.PENDING,
            debtorAccountId = debtorAccount,
            debtorIban = iban(debtorAccount),
            debtorName = "Simulated Instant Debtor",
            creditorIban = iban(creditorAccount),
            creditorName = "Simulated Instant Creditor",
            creditorBic = CREDITOR_BIC,
            amount = Money.of(amount, SctInstSchemeRules.SCHEME_CURRENCY.code),
            remittanceInfo = null,
            endToEndId = "e2e-${random.nextUuid()}",
            executionTimeoutAt = null,
            settledAt = null,
            recalledAt = null,
            recallReason = null,
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        SctInstSchemeRules.requireSchemeCurrency(payment.amount)
        world.sctInstPayments.add(payment)

        val settlement = Settlement(
            id = random.nextUuid(),
            payerAccountId = debtorAccount,
            payeeAccountId = creditorAccount,
            amount = amount,
            currency = SctInstSchemeRules.SCHEME_CURRENCY.code,
            status = SettlementStatus.PENDING,
            createdAt = world.context.clock.instant(),
            updatedAt = world.context.clock.instant(),
        )
        world.settlements.add(settlement)

        return SctInstLeg(payment, settlement, world.sctInstPayments.lastIndex, world.settlements.lastIndex)
    }

    private fun iban(accountId: UUID): String = "CZ0000000000$accountId".take(IBAN_PREFIX_LENGTH)

    /** The pair of real domain aggregates driven together by one scenario step. */
    private data class SctInstLeg(
        val payment: SctInstPayment,
        val settlement: Settlement,
        val paymentIndex: Int,
        val settlementIndex: Int,
    ) {
        /**
         * CLEAR: release the instant payment to PROCESSING and arm the scheme execution timeout,
         * mirroring the production `proceed` step (`executionTimeoutAt = now + timeout`).
         */
        fun proceed(world: World): SctInstLeg = storePayment(
            world,
            payment.copy(
                status = SctInstStatus.PROCESSING,
                submittedAt = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC),
                executionTimeoutAt = OffsetDateTime.ofInstant(
                    world.context.clock.instant().plusSeconds(EXECUTION_TIMEOUT_SECONDS),
                    ZoneOffset.UTC,
                ),
            ),
        ).also { world.audit.append("sct-inst ${it.payment.id} -> PROCESSING") }

        /**
         * Complete PROCESSING -> SETTLED, mirroring the production `complete` step, whose
         * optimistic guard (`updateWithEvent` expecting PROCESSING) makes a settle of a
         * non-PROCESSING payment a conflict, not a silent state jump.
         */
        fun complete(world: World): SctInstLeg {
            check(payment.status == SctInstStatus.PROCESSING) {
                "only PROCESSING payments can settle (saw ${payment.status})"
            }
            val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
            return storePayment(
                world,
                payment.copy(status = SctInstStatus.SETTLED, settledAt = now, updatedAt = now),
            ).also { world.audit.append("sct-inst ${it.payment.id} -> SETTLED") }
        }

        /**
         * Recall a SETTLED payment, mirroring the production `recall` precondition — the service
         * refuses with a 400 when the payment is not SETTLED, so a recall attempted from any
         * other state is a contract violation, not a transition.
         */
        fun recall(world: World, reason: String): SctInstLeg {
            check(payment.status == SctInstStatus.SETTLED) {
                "only SETTLED payments can be recalled (saw ${payment.status})"
            }
            val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
            return storePayment(
                world,
                payment.copy(
                    status = SctInstStatus.RECALLED,
                    recalledAt = now,
                    recallReason = reason,
                    updatedAt = now,
                ),
            ).also { world.audit.append("sct-inst ${it.payment.id} -> RECALLED") }
        }

        fun advancePayment(world: World, to: SctInstStatus): SctInstLeg = storePayment(
            world,
            payment.copy(
                status = to,
                updatedAt = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC),
            ),
        )

        fun advanceSettlement(world: World, to: SettlementStatus): SctInstLeg {
            val next: Settlement = settlement.copy(status = to, updatedAt = world.context.clock.instant())
            world.settlements[settlementIndex] = next
            world.audit.append("settlement ${next.id} -> ${next.status}")
            return copy(settlement = next)
        }

        fun storePayment(world: World, next: SctInstPayment): SctInstLeg {
            world.sctInstPayments[paymentIndex] = next
            return copy(payment = next)
        }
    }
}
