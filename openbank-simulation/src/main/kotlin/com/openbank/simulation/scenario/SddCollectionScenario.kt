// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.sdd.domain.lifecycle.MandateLifecycle
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.simulation.adapters.AccountBookedChanged
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One step of the SEPA Direct Debit collection money path (ADR-0036): a creditor collection
 * against the **real `SddMandate` aggregate** driven through the **real `MandateLifecycle`**
 * pure state machine, posting the debit through the **real `JournalEntry` aggregate** — the
 * ADR-0100 "build on the real system" convention, extending harness coverage to the direct-debit
 * (collection) rail to complement the credit-transfer rails (PaymentScenario, SepaSettlementScenario,
 * StandingOrderScenario).
 *
 * Lifecycle subtleties mirrored from production, because a re-model would miss them:
 *  - A seeded share of new mandates is B2B, born `PENDING_CONFIRMATION`; the step confirms it
 *    through [MandateLifecycle.confirm] before any collection can fire (the debtor-bank
 *    confirmation gate, ADR-0036 §B).
 *  - A successful collection advances FRST → RCUR and stamps `lastCollectionDate` through
 *    [MandateLifecycle.recordCollection] — the same stamp the EPC 36-month idle-expiry anchor
 *    ([MandateLifecycle.lastActivity]) reads.
 *  - The step applies [MandateLifecycle.expireIfIdle] to any mandate it touches: a live mandate
 *    idle for [MandateLifecycle.IDLE_EXPIRY_MONTHS] must leave the step EXPIRED, never collected
 *    — auto-expiry is part of the collection precondition, not a background job.
 *  - Pre-notification and amendments are tracked (`recordPreNotification`, `amend`) the way the
 *    production step does — stamped, never enforced — so the mandate shape the next collection
 *    carries matches production.
 *
 * Fault semantics match the other scenarios: a write fault means the ledger call failed before
 * committing (nothing posted, hold released, failure recorded); a post-commit conflict reverses
 * the durable journal before recording the failure. An overdraft decline is a legitimate
 * rejected collection (RJCT in SDD terms), recorded as a failed attempt.
 */
object SddCollectionScenario {

    private const val MAX_MINOR_UNITS = 200_000L // up to 2000.00 — a modest collection amount
    private const val NEW_MANDATE_FRACTION = 10 // nextInt(10) < 4 ⇒ a 40% seeded chance to open a new mandate
    private const val NEW_MANDATE_THRESHOLD = 4
    private const val SCHEME_FRACTION = 4 // nextInt(4) == 0 ⇒ B2B, otherwise CORE
    private const val B2B_MARK = 0
    private const val SEQUENCE_FRACTION = 3 // nextInt(3) == 0 ⇒ FRST, otherwise RCUR
    private const val FRST_MARK = 0
    private const val PRE_NOTIFICATION_FRACTION = 4 // nextInt(4) == 0 ⇒ stamp a pre-notification
    private const val PRE_NOTIFICATION_MARK = 0
    private const val AMEND_FRACTION = 5 // nextInt(5) == 0 ⇒ record a creditor amendment (AMDT)
    private const val AMEND_MARK = 0
    private const val UMR_MODULO = 100_000_000 // deterministic UMR suffix from the mandate id
    private const val UMR_LENGTH = 14
    private const val DEBTOR_IBAN_LENGTH = 22
    private val ZONE = ZoneId.of("Europe/Prague")

    // The same shared deposit-control GL the other scenarios post against; the customer
    // dimension is carried by JournalLine.subAccountId (what bookedDeltas() projects on).
    private val DEPOSIT_CONTROL_GL: UUID = UUID(0L, 1L)
    private val SYSTEM_ACTOR: UUID = UUID(0L, 2L)

    fun step(world: World) {
        val random = world.context.random
        val faults = world.context.faults
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZONE)

        // 1-3. Pick the mandate (reuse live or seed a new one), expire-if-idle, stamp tracking events.
        val selection = selectMandate(world) ?: return
        val (ready, index) = selection

        // The creditor settlement account stands in for the external creditor's pocket: every
        // subAccountId a collection journal touches must carry a balance or the projection
        // invariant cannot reconcile it.
        val debtor = AccountCurrency(ready.accountId, world.currency)
        val creditorAccount = world.customerAccounts.first { it != ready.accountId }
        val money = Money.of(BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2), world.currency)
        val amount = money.amount

        // 4. Reserve funds on the debtor. A floor breach is a legitimate RJCT, not a bug.
        try {
            world.balances.put(world.balances.get(debtor).withReservation(amount))
        } catch (_: IllegalArgumentException) {
            world.sddCollections.recordAttemptFailure(ready.id)
            world.audit.append("sdd collection ${ready.id} rejected: insufficient funds")
            return
        }

        // 5. Post the balanced double-entry journal as the real aggregate. The idempotency key is
        //    unique per collection attempt (the mandate's running attempt count), never per
        //    mandate-day: a mandate can legitimately be collected several times on the same day,
        //    and each attempt is a new journal — a replayed key would return a previous entry the
        //    step never posted (its reverse would throw) while its deltas got double-published.
        val entry: JournalEntry
        try {
            if (faults.shouldFailWrite()) throw SimulatedWriteFailure("ledger post failed for ${ready.id}")
            entry = buildCollection(world, ready, creditorAccount, money)
            val attempt = world.sddCollections.postedCollections(ready.id) +
                world.sddCollections.attemptedFailureCount(ready.id)
            val posted = world.ledger.post("sdd-collection-${ready.id}-$attempt", entry)
            publishBooked(world, posted)
        } catch (e: SimulatedWriteFailure) {
            // Nothing durable: release the hold, record the failed attempt, mandate untouched.
            world.balances.put(world.balances.get(debtor).releaseReservation(amount))
            world.sddCollections.recordAttemptFailure(ready.id)
            world.audit.append("sdd collection ${ready.id} compensated: ${e.message}")
            return
        }

        // 6. A post-commit fault forces the same compensation AFTER the journal is durable.
        if (faults.shouldConflict()) {
            val reversal = world.ledger.reverse(
                entry.id,
                random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { random.nextUuid() },
            )
            publishBooked(world, reversal)
            world.balances.put(world.balances.get(debtor).releaseReservation(amount))
            world.sddCollections.recordAttemptFailure(ready.id)
            world.audit.append("sdd collection ${ready.id} compensated: post-commit conflict")
            return
        }

        // 7. Happy path: release the hold, stamp the mandate through the real lifecycle, and
        //    record the collection on the money-path side of the book.
        world.balances.put(world.balances.get(debtor).releaseReservation(amount))
        world.sddMandates[index] = MandateLifecycle.recordCollection(ready, today)
        world.sddCollections.recordCollected(ready.id, amount, today)
        world.audit.append("sdd collection ${ready.id} settled")
    }

    /**
     * Steps 1-3 of the step: pick a live mandate or seed a new one (B2B born PENDING_CONFIRMATION
     * is confirmed up front), apply the idle-expiry precondition, and stamp the seeded
     * tracking-only events. Returns the mandate and its index, or null when the mandate expired
     * in this step (nothing further may happen).
     */
    private fun selectMandate(world: World): Pair<SddMandate, Int>? {
        val random = world.context.random
        val accounts = world.customerAccounts
        val now = world.context.clock.instant()
        val today = LocalDate.ofInstant(now, ZONE)

        // 1. Pick the mandate to collect: reuse a live one, or open a new seeded one.
        val live = world.sddMandates.filter { it.status == MandateStatus.ACTIVE }
        val mandate = when {
            live.isEmpty() || random.nextInt(NEW_MANDATE_FRACTION) < NEW_MANDATE_THRESHOLD ->
                createMandate(world, accounts, today, now).also(world.sddMandates::add)
            else -> live[random.nextInt(live.size)]
        }
        val index = world.sddMandates.indexOfFirst { it.id == mandate.id }

        // 2. Auto-expiry is part of the collection precondition (ADR-0036 §B): a live mandate
        //    idle for 36 months leaves this step EXPIRED and is never collected.
        val unexpired = MandateLifecycle.expireIfIdle(mandate, today)
        world.sddMandates[index] = unexpired
        if (unexpired.status != MandateStatus.ACTIVE) {
            world.audit.append("sdd mandate ${mandate.id} expired before collection")
            return null
        }

        // 3. Seeded tracking-only events, stamped exactly as the production step does.
        var ready = unexpired
        if (random.nextInt(PRE_NOTIFICATION_FRACTION) == PRE_NOTIFICATION_MARK) {
            ready = MandateLifecycle.recordPreNotification(ready, today)
        }
        if (random.nextInt(AMEND_FRACTION) == AMEND_MARK) {
            ready = MandateLifecycle.amend(ready, "creditorName", "Sim Creditor", "Sim Creditor Amended", now)
        }
        world.sddMandates[index] = ready
        return ready to index
    }

    private fun createMandate(world: World, accounts: List<UUID>, today: LocalDate, now: Instant): SddMandate {
        val random = world.context.random
        val debtor = accounts[random.nextInt(accounts.size)]
        // B2B mandates are born PENDING_CONFIRMATION and must be confirmed before use; Core may
        // be born ACTIVE. Both shapes are seeded so the confirm gate is exercised, not skipped.
        val scheme = if (random.nextInt(SCHEME_FRACTION) == B2B_MARK) SddScheme.B2B else SddScheme.CORE
        val born = SddMandate(
            id = random.nextUuid(),
            accountId = debtor,
            debtorIban = "CZ65SIM${abs(debtor.hashCode()) % UMR_MODULO}".padEnd(DEBTOR_IBAN_LENGTH, '0'),
            creditorIdentifier = "SIM-CREDITOR-${abs(debtor.hashCode()) % UMR_MODULO}",
            umr = "SIM-UMR-${abs(random.nextUuid().hashCode()) % UMR_MODULO}".padEnd(UMR_LENGTH, 'X'),
            scheme = scheme,
            sequenceType = if (random.nextInt(SEQUENCE_FRACTION) == FRST_MARK) SequenceType.FRST else SequenceType.RCUR,
            creditorName = "Sim Creditor",
            debtorName = "Sim Debtor",
            signatureDate = today,
            status = if (scheme == SddScheme.B2B) MandateStatus.PENDING_CONFIRMATION else MandateStatus.ACTIVE,
            b2bConfirmed = false,
            lastCollectionDate = null,
            lastPreNotificationDate = null,
            createdAt = now,
        )
        // The debtor-bank confirmation gate (ADR-0036 §B): a B2B mandate cannot be collected
        // until confirmed; the step pays the confirmation cost up front, like the real flow.
        return if (born.status == MandateStatus.PENDING_CONFIRMATION) MandateLifecycle.confirm(born) else born
    }

    /** Build the real POSTED double-entry collection: debit debtor, credit creditor pocket. */
    private fun buildCollection(world: World, mandate: SddMandate, creditorAccount: UUID, money: Money): JournalEntry {
        val random = world.context.random
        val journalId = random.nextUuid()
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZONE)
        val debit = JournalLine(
            id = lineId(journalId, 1L),
            journalId = journalId,
            glAccountId = DEPOSIT_CONTROL_GL,
            side = JournalSide.DEBIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 1,
            subAccountId = mandate.accountId,
        )
        val credit = JournalLine(
            id = lineId(journalId, 2L),
            journalId = journalId,
            glAccountId = DEPOSIT_CONTROL_GL,
            side = JournalSide.CREDIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 2,
            subAccountId = creditorAccount,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = mandate.id,
            entryDate = today,
            valueDate = today,
            description = "sdd collection ${mandate.id}",
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    private fun lineId(journalId: UUID, sequence: Long): UUID = UUID(journalId.mostSignificantBits, sequence)

    /** Project the entry's real `bookedDeltas()` (credit-positive, deposit-control) onto the bus. */
    private fun publishBooked(world: World, entry: JournalEntry) {
        entry.bookedDeltas().forEach { delta ->
            world.bus.publish(
                AccountBookedChanged(entry.id, AccountCurrency(delta.accountId, delta.currency), delta.delta),
            )
        }
    }

    private fun abs(value: Int): Int = if (value < 0) -value else value
}
