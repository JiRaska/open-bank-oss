// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.adapters.AccountBookedChanged
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.World
import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.PaymentType
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.domain.model.StandingOrderStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One simulated day of the standing-order money path, modelled directly against the **real
 * `openbank-standing-order-service` domain aggregate** (`StandingOrder.recordExecution` /
 * `recordFailure` / `confirmExecution` / `calculateNextDate`) and the **real
 * `openbank-ledger-service` `JournalEntry`** — not a re-model of either, mirroring
 * [PaymentScenario]/[FeeBillingScenario]/[SepaSettlementScenario]'s "build on the real system"
 * convention (ADR-0100).
 *
 * Each step IS one calendar day: the production scheduler is a daily cron
 * (`StandingOrderExecutionScheduler`), so the scenario advances the virtual clock by a day at
 * the start of every step, places a new order on a seeded customer account with some
 * probability, and then runs the exact two-phase production shape over every due order:
 *
 * 1. **The sweep** (`StandingOrderService.executeOrders`): due means
 *    `status == ACTIVE && nextExecutionDate <= today` — the scenario drives the real
 *    `recordExecution(calculateNextDate(nextExecutionDate), now)`, which advances the schedule
 *    BEFORE the payment is attempted and, for a recurring order whose next date passed its
 *    `endDate`, completes it right there.
 * 2. **The execution** (`StandingOrderDueConsumer`): the payment is attempted against the real
 *    in-memory ledger/balance stores with the real fault injector in the loop; the outcome is
 *    reported back through the real aggregate — success ⇒ `confirmExecution` (resets
 *    `failureCount`, completes a ONCE order), rejection or injected write fault ⇒
 *    `recordFailure` (three consecutive failures ⇒ `FAILED`, the aggregate's own rule).
 *
 * The idempotency shape is load-bearing: the payment attempt is keyed by
 * `(orderId, executionDate)` — the exact date dimension of production's
 * `so-exec-{orderId}-{executionDate}` key — and a re-visited pair replays the CACHED outcome
 * (confirmed ⇒ re-confirm without posting again; failed ⇒ re-fail) instead of moving new money.
 * That is what makes `MoneyPathInvariants.standingOrderExecutionConservation` assertable: every
 * confirmed execution date has posted exactly the order amount, and no other execution did —
 * a duplicate delivery (the shared FaultProfile's `duplicateEventRate`) or a stuck ONCE order
 * (issue #12839's defect class) shows up as posted money without a confirmed date, or as two
 * confirmed dates on a ONCE order, not as a balanced-looking ledger.
 */
object StandingOrderExecutionScenario {

    // Up to 2000.00 — rent-sized, well under the 10000.00 opening balance.
    private const val MAX_AMOUNT_MINOR_UNITS = 200_000L
    private const val ONCE_ORDER_RATE = 0.20 // seeded fraction of NEW orders that are one-off
    private const val NEW_ORDER_RATE = 0.60 // seeded fraction of steps that place a new order on a free account
    private const val MAX_END_DATE_DAYS = 4L // recurring orders live up to this many days past placement

    // Dedicated, named least-significant bits (FeeBillingScenario's convention) so a trace dump
    // can tell standing-order journals apart from the other scenarios' GL accounts.
    private const val STANDING_ORDER_CONTROL_GL_LSB = 6L
    private const val SYSTEM_ACTOR_LSB = 7L
    private val STANDING_ORDER_CONTROL_GL: UUID = UUID(0L, STANDING_ORDER_CONTROL_GL_LSB)
    private val SYSTEM_ACTOR: UUID = UUID(0L, SYSTEM_ACTOR_LSB)

    private val ZONE = ZoneId.of("Europe/Prague")

    fun step(world: World) {
        val random = world.context.random
        // One step == one calendar day: the real scheduler is a daily cron. Advancing here (not
        // in the runner) keeps the day-per-step convention local to the scenario whose production
        // counterpart actually ticks daily.
        world.context.clock.advance(Duration.ofDays(1))
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZONE)
        val now = world.context.clock.instant()

        if (random.chance(NEW_ORDER_RATE)) {
            placeOrder(world, today, now)
        }
        world.standingOrders.all()
            .filter { it.status == StandingOrderStatus.ACTIVE && !it.nextExecutionDate.isAfter(today) }
            .forEach { executeDue(world, it, now) }
    }

    /** Seed a new ACTIVE order on a customer account that has none, due today. */
    private fun placeOrder(world: World, today: LocalDate, now: Instant) {
        val random = world.context.random
        val busyAccounts = world.standingOrders.all().map { it.debitAccountId }.toSet()
        val freeAccount = world.customerAccounts.firstOrNull { it !in busyAccounts } ?: return
        val frequency = if (random.chance(ONCE_ORDER_RATE)) Frequency.ONCE else Frequency.DAILY
        val order = StandingOrder(
            id = random.nextUuid(),
            idempotencyKey = "sim-so-${world.context.seed}-${random.nextLong()}",
            partyId = random.nextUuid(),
            debitAccountId = freeAccount,
            // SEPA_CREDIT is the only type the real consumer routes to the sepa rail; the
            // debtor details the rail requires are present so the execution path is the real one.
            debtorIban = "CZ6508000000192000145399",
            debtorName = "Trace Sim Customer",
            creditorIban = "CZ6508000000192000145400",
            creditorName = "Sim Creditor",
            creditorBic = null,
            amountMinorUnits = random.nextLong(1L, MAX_AMOUNT_MINOR_UNITS),
            currency = world.currency,
            frequency = frequency,
            paymentType = PaymentType.SEPA_CREDIT,
            remittanceInfo = null,
            startDate = today,
            endDate = if (frequency == Frequency.ONCE) null else today.plusDays(random.nextLong(0L, MAX_END_DATE_DAYS)),
            nextExecutionDate = today,
            lastExecutionDate = null,
            executionCount = 0,
            failureCount = 0,
            status = StandingOrderStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        world.standingOrders.place(order)
        world.audit.append("standing order ${order.id} placed: ${order.frequency} ${order.amountMinorUnits} minor")
    }

    /** The sweep + consumer pair for one due order, exactly in production's call order. */
    private fun executeDue(world: World, due: StandingOrder, now: Instant) {
        val random = world.context.random
        val executionDate = due.nextExecutionDate

        // Phase 1 — the sweep: recordExecution advances the schedule BEFORE the payment attempt
        // (its KDoc documents why completing a ONCE order here would fake success on failure).
        val swept = due.recordExecution(due.calculateNextDate(executionDate), now)
        world.standingOrders.replace(swept)
        world.audit.append("standing order ${due.id} swept for $executionDate (count=${swept.executionCount})")

        // Phase 2 — the execution, keyed by (orderId, executionDate) like the so-exec- key. A
        // re-visited pair is a REPLAY, not a new attempt: the rail's idempotency cache answers
        // with the original outcome.
        if (executionDate in world.standingOrders.view(due.id).attemptedDates) {
            replayCachedOutcome(world, swept, executionDate, now)
            return
        }
        world.standingOrders.recordAttempt(due.id, executionDate)

        val amount = BigDecimal(swept.amountMinorUnits).movePointLeft(2)
        val source = AccountCurrency(swept.debitAccountId, swept.currency)

        // A floor breach is a legitimate decline — the rail rejects and the consumer records it.
        val reserved = try {
            world.balances.put(world.balances.get(source).withReservation(amount))
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!reserved) {
            recordFailure(world, swept, executionDate, now, "insufficient funds")
            return
        }

        var entryId: UUID? = null
        try {
            if (world.context.faults.shouldFailWrite()) {
                throw SimulatedWriteFailure("standing-order payment write failed for ${swept.id}")
            }
            val entry = buildExecutionJournal(world, swept, amount, executionDate)
            world.ledger.post("so-exec-${swept.id}-$executionDate", entry)
            publishBooked(world, entry)
            entryId = entry.id
        } catch (e: SimulatedWriteFailure) {
            // Nothing posted: release the hold, report the failure — compensate, not swallow.
            world.balances.put(world.balances.get(source).releaseReservation(amount))
            recordFailure(world, swept, executionDate, now, e.message)
            return
        }

        // A post-commit conflict reverses the posting and reports failure, exactly the
        // compensate-after-commit shape PaymentScenario exercises for transfers.
        if (world.context.faults.shouldConflict()) {
            val reversal = world.ledger.reverse(
                checkNotNull(entryId) { "post succeeded, entry id must be present" },
                random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { random.nextUuid() },
            )
            publishBooked(world, reversal)
            world.balances.put(world.balances.get(source).releaseReservation(amount))
            recordFailure(world, swept, executionDate, now, "post-commit conflict")
            return
        }

        // Happy path: release the hold, post the confirmation, record the confirmation.
        world.balances.put(world.balances.get(source).releaseReservation(amount))
        world.standingOrders.recordPosted(swept.id, amount)
        world.standingOrders.recordConfirmed(swept.id, executionDate)
        val confirmed = world.standingOrders.view(swept.id).order ?: swept
        if (confirmed.status == StandingOrderStatus.ACTIVE) {
            world.standingOrders.replace(confirmed.confirmExecution(now))
        }
        world.audit.append("standing order ${swept.id} executed for $executionDate: journal posted")
    }

    /** A re-delivered due event replays the cached outcome — never a second payment. */
    private fun replayCachedOutcome(world: World, order: StandingOrder, executionDate: LocalDate, now: Instant) {
        if (executionDate in world.standingOrders.view(order.id).confirmedDates) {
            // The rail answered the replay with the cached 201: confirm again (idempotent for
            // recurring orders; for a ONCE order this is the retry that finally completes it).
            if (order.status == StandingOrderStatus.ACTIVE) {
                world.standingOrders.replace(order.confirmExecution(now))
            }
            world.audit.append("standing order ${order.id} replayed cached success for $executionDate")
        } else {
            recordFailure(world, order, executionDate, now, "cached decline replayed")
        }
    }

    private fun recordFailure(
        world: World,
        order: StandingOrder,
        executionDate: LocalDate,
        now: Instant,
        reason: String?,
    ) {
        val current = world.standingOrders.view(order.id).order ?: order
        // recordFailure requires ACTIVE (the sweep's recordExecution may have completed a
        // recurring order past its endDate before the payment failed — that order is done, and
        // there is no failure left to record on it).
        if (current.status == StandingOrderStatus.ACTIVE) {
            val failed = current.recordFailure(now)
            world.standingOrders.replace(failed)
            world.audit.append("standing order ${order.id} execution $executionDate failed: $reason")
        }
    }

    /** DEBIT the debtor's deposit control (subAccountId=debtor), CREDIT the standing-order control GL. */
    private fun buildExecutionJournal(
        world: World,
        order: StandingOrder,
        amount: BigDecimal,
        executionDate: LocalDate,
    ): JournalEntry {
        val journalId = world.context.random.nextUuid()
        val money = Money.of(amount, order.currency)
        val debit = JournalLine(
            id = lineId(journalId, 1L),
            journalId = journalId,
            glAccountId = STANDING_ORDER_CONTROL_GL,
            side = JournalSide.DEBIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 1,
            subAccountId = order.debitAccountId,
        )
        val credit = JournalLine(
            id = lineId(journalId, 2L),
            journalId = journalId,
            glAccountId = STANDING_ORDER_CONTROL_GL,
            side = JournalSide.CREDIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 2,
            subAccountId = null,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = world.context.random.nextUuid(),
            entryDate = executionDate,
            valueDate = executionDate,
            description = "standing order ${order.id} execution $executionDate",
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    private fun lineId(journalId: UUID, sequence: Long): UUID = UUID(journalId.mostSignificantBits, sequence)

    /** Project the entry's real `bookedDeltas()` onto the bus — same convention as the other scenarios. */
    private fun publishBooked(world: World, entry: JournalEntry) {
        entry.bookedDeltas().forEach { delta ->
            world.bus.publish(
                AccountBookedChanged(entry.id, AccountCurrency(delta.accountId, delta.currency), delta.delta),
            )
        }
    }
}
