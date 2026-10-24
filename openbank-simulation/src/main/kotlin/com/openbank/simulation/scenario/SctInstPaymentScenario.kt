// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.simulation.scenario

import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.sepainstant.domain.error.SctInstSchemeRules
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Money-path SCT Inst adoption (ADR-0100 full-service family, EPC SCT Inst rulebook): one step
 * of the instant-payment path, modelled directly against the **real `sepa-instant`
 * `SctInstPayment`** aggregate and the **real `SctInstSchemeRules.requireSchemeCurrency`
 * admission rule** (#11913, EUR-only) — not a re-model of either. The domain package is
 * framework-free (ADR-0002), so only the domain POJOs land on the classpath.
 *
 * SCT Inst is euro-only, while the simulated customer accounts open in the world's configured
 * currency, so the settlement leg posts a real balanced `JournalEntry` **in EUR**: the
 * conservation invariant nets per currency (the EUR legs always balance), and the
 * ledger→balance projection invariant only asserts over opened balances, so an unprojected
 * EUR entry is not a projection gap — instant settlement is a scheme-level movement the
 * customer's CZK balance projection never sees.
 *
 * A step, mirroring [SepaSettlementScenario]'s compensation completeness:
 *  1. admit through the real scheme rule (a non-EUR amount is a legitimate 400, never a bug);
 *  2. an injected write fault at the settlement post → REJECTED before any money moved;
 *  3. a post-commit conflict after the EUR journal is durable → the real `JournalEntry.reverse()`
 *     unwinds it and the payment is RECALLED — money conserved end-to-end;
 *  4. the scheme timeout fires after acceptance but before settlement: the real
 *     `JournalEntry.reverse()` unwinds the durable EUR journal before the payment dies in
 *     TIMEOUT — an instant payment that never settles must not keep its money movement (the
 *     draw sits after the fault branches so a forced `FaultProfile(1.0)` is deterministic);
 *  5. happy path: the balanced EUR journal posts once and the payment settles (SETTLED).
 *
 * Every payment reaches a terminal status within the step, which is what
 * `MoneyPathInvariants.sctInstCompleteness` asserts after the scheduler drains.
 */
object SctInstPaymentScenario {

    private const val MAX_MINOR_UNITS = 500_000L // up to 5000.00 in a 2-dp currency
    private const val IBAN_PREFIX_LENGTH = 24
    private const val CREDITOR_BIC = "OPENBACEPXXX"
    private const val EXECUTION_TIMEOUT_SECONDS = 10L
    private const val TIMEOUT_RATE = 0.10

    // A single shared scheme-settlement GL account; the customer dimension is carried by
    // JournalLine.subAccountId, exactly as PaymentScenario's deposit control does. The credit
    // leg lands on a dedicated scheme-house counterparty (SctInstPayment carries the creditor's
    // IBAN, not a local account id — from the debtor bank's books the funds move into the
    // scheme's settlement house). Named least-significant-bits, same convention as
    // FeeBillingScenario's GL constants (bare UUID(0L, 1L) style trips detekt's MagicNumber).
    private const val SCHEME_SETTLEMENT_GL_LSB = 11L
    private const val SYSTEM_ACTOR_LSB = 12L
    private const val SCHEME_HOUSE_LSB = 13L
    private val SCHEME_SETTLEMENT_GL: UUID = UUID(0L, SCHEME_SETTLEMENT_GL_LSB)
    private val SYSTEM_ACTOR: UUID = UUID(0L, SYSTEM_ACTOR_LSB)
    private val SCHEME_HOUSE: UUID = UUID(0L, SCHEME_HOUSE_LSB)

    fun step(world: World) {
        val leg = openLeg(world)
        val journalId = world.context.random.nextUuid()

        // 2. An injected write fault at the settlement post means the EUR journal never
        //    committed: reject the payment before any money moved.
        if (settle(world, leg, journalId)) return

        // 3. A post-commit conflict after the journal is durable: unwind and recall.
        if (recall(world, leg, journalId)) return

        // 4. Scheme timeout after acceptance: unwind the journal and die terminal.
        if (timeout(world, leg, journalId)) return

        // 5. Happy path: exactly one balanced EUR journal is durable and the payment settles.
        leg.advance(world, SctInstStatus.SETTLED, settled = true)
    }

    private fun openLeg(world: World): SctInstLeg {
        val random = world.context.random
        val accounts = world.customerAccounts
        val debtorAccount = accounts[random.nextInt(accounts.size)]
        var creditorAccount = accounts[random.nextInt(accounts.size)]
        if (creditorAccount == debtorAccount) {
            creditorAccount = accounts[(accounts.indexOf(creditorAccount) + 1) % accounts.size]
        }

        val amount = BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2)
        // The real admission rule: SCT Inst settles in EUR only (#11913). Passing EUR here is
        // what production's inbound boundary does after kernel Money validation.
        val eur = Money.of(amount, SctInstSchemeRules.SCHEME_CURRENCY.code)
        SctInstSchemeRules.requireSchemeCurrency(eur)

        val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        val payment = SctInstPayment(
            id = world.sctInstPayments.size.toLong(),
            paymentId = random.nextUuid(),
            idempotencyKey = "sctinst-${random.nextUuid()}",
            status = SctInstStatus.PENDING,
            debtorAccountId = debtorAccount,
            debtorIban = iban(debtorAccount),
            debtorName = "Simulated Inst Debtor",
            creditorIban = iban(creditorAccount),
            creditorName = "Simulated Inst Creditor",
            creditorBic = CREDITOR_BIC,
            amount = eur,
            remittanceInfo = null,
            endToEndId = "e2e-${random.nextUuid()}",
            executionTimeoutAt = now.plusSeconds(EXECUTION_TIMEOUT_SECONDS),
            settledAt = null,
            recalledAt = null,
            recallReason = null,
            rejectReason = null,
            rejectDetail = null,
            submittedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        world.sctInstPayments.add(payment)
        return SctInstLeg(payment, world.sctInstPayments.lastIndex)
    }

    /** Posts the settlement journal; returns true when a write fault rejected the payment. */
    private fun settle(world: World, leg: SctInstLeg, journalId: UUID): Boolean = try {
        if (world.context.faults.shouldFailWrite()) {
            throw SimulatedWriteFailure("sct-inst settlement post failed for ${leg.payment.paymentId}")
        }
        val entry = buildSettlementEntry(world, journalId, leg.payment)
        world.ledger.post("sctinst-${leg.payment.paymentId}-settlement", entry)
        false
    } catch (e: SimulatedWriteFailure) {
        leg.advance(world, SctInstStatus.REJECTED, reject = "TECHNICAL_ERROR: ${e.message}")
        true
    }

    /** Post-commit conflict: the real reverse unwinds the journal and the payment is recalled. */
    private fun recall(world: World, leg: SctInstLeg, journalId: UUID): Boolean {
        if (!world.context.faults.shouldConflict()) return false
        reverse(world, journalId)
        leg.advance(world, SctInstStatus.RECALLED, recall = true, reject = "POST_COMMIT_CONFLICT")
        return true
    }

    /** Scheme timeout: an unsettled instant payment must not keep its money movement. */
    private fun timeout(world: World, leg: SctInstLeg, journalId: UUID): Boolean {
        if (!world.context.random.chance(TIMEOUT_RATE)) return false
        reverse(world, journalId)
        leg.advance(world, SctInstStatus.TIMEOUT)
        return true
    }

    private fun reverse(world: World, journalId: UUID) {
        world.ledger.reverse(
            journalId,
            world.context.random.nextUuid(),
            SYSTEM_ACTOR,
            lineIdProvider = { world.context.random.nextUuid() },
        )
    }

    private fun buildSettlementEntry(world: World, journalId: UUID, payment: SctInstPayment): JournalEntry {
        val amount = payment.amount
        val today = java.time.LocalDate.ofInstant(world.context.clock.instant(), java.time.ZoneOffset.UTC)
        val debit = JournalLine(
            id = lineId(journalId, 1L),
            journalId = journalId,
            glAccountId = SCHEME_SETTLEMENT_GL,
            side = JournalSide.DEBIT,
            amount = amount,
            fxRate = null,
            baseAmount = amount,
            sequence = 1,
            subAccountId = payment.debtorAccountId,
        )
        val credit = JournalLine(
            id = lineId(journalId, 2L),
            journalId = journalId,
            glAccountId = SCHEME_SETTLEMENT_GL,
            side = JournalSide.CREDIT,
            amount = amount,
            fxRate = null,
            baseAmount = amount,
            sequence = 2,
            subAccountId = SCHEME_HOUSE,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = payment.paymentId,
            entryDate = today,
            valueDate = today,
            description = "sct-inst settlement ${payment.paymentId}",
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    private fun lineId(journalId: UUID, sequence: Long): UUID = UUID(journalId.mostSignificantBits, sequence)

    private fun iban(accountId: UUID): String = "DE0000000000$accountId".take(IBAN_PREFIX_LENGTH)

    /** The real domain aggregate driven by one scenario step, with its index into [World]. */
    private data class SctInstLeg(val payment: SctInstPayment, val index: Int) {
        fun advance(
            world: World,
            to: SctInstStatus,
            settled: Boolean = false,
            recall: Boolean = false,
            reject: String? = null,
        ): SctInstLeg {
            val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
            val next = payment.copy(
                status = to,
                settledAt = if (settled) now else payment.settledAt,
                recalledAt = if (recall) now else payment.recalledAt,
                rejectReason = reject ?: payment.rejectReason,
                updatedAt = now,
            )
            world.sctInstPayments[index] = next
            world.audit.append("sct-inst ${next.paymentId} -> ${next.status}")
            return copy(payment = next)
        }
    }
}
