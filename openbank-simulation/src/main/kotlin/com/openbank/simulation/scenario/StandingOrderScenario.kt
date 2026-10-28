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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One step of the standing-order money path: a scheduled INTERNAL recurring payment driven by the
 * **real `StandingOrder` status machine** (com.openbank.standingorder.domain.model) against the
 * **real `JournalEntry` aggregate** — the ADR-0100 "build on the real system" convention, extending
 * the harness coverage to the recurring-payment rail (issue #667's E2E money-path family).
 *
 * The step mirrors the production sweep executor's ordering, including the deliberate subtlety the
 * real domain encodes: [StandingOrder.recordExecution] runs BEFORE the payment is attempted (it
 * advances `nextExecutionDate` and `executionCount` and completes an ended recurring order), while
 * [StandingOrder.confirmExecution] runs only after money actually moved — so a failed attempt must
 * go through [StandingOrder.recordFailure] and the count on the status machine stays consistent
 * with what the ledger/balance state shows. Both sides are recorded into [World.standingOrdersBook]
 * and reconciled by `MoneyPathInvariants.standingOrderCompleteness`.
 *
 * Fault semantics match [PaymentScenario]: a write fault means the ledger call failed before
 * committing (nothing posted, hold released, failure recorded); a post-commit conflict reverses
 * the durable journal before recording the failure. An overdraft decline is a legitimate failure
 * outcome (the sweep does not force money the account does not have), recorded as a failure too.
 */
object StandingOrderScenario {

    private const val MAX_MINOR_UNITS = 100_000L // up to 1000.00 — a modest recurring charge
    private const val NEW_ORDER_FRACTION = 10 // nextInt(10) < 3 ⇒ a 30% seeded chance to open a new order
    private const val NEW_ORDER_THRESHOLD = 3
    private const val FREQUENCY_FRACTION = 10 // nextInt(10) in 0..1 ⇒ ONCE, otherwise MONTHLY
    private const val ONCE_THRESHOLD = 1
    private const val IBAN_CHECK_DIGITS_MODULO = 100_000_000 // deterministic IBAN suffix from the account id
    private const val IBAN_LENGTH = 22
    private const val END_DATE_MONTHS_MAX = 13L // nextLong(1, 13) ⇒ 1–12 months out
    private val ZONE = ZoneId.of("Europe/Prague")

    // The same shared deposit-control GL the other scenarios post against; the customer
    // dimension is carried by JournalLine.subAccountId (what bookedDeltas() projects on).
    private val DEPOSIT_CONTROL_GL: UUID = UUID(0L, 1L)
    private val SYSTEM_ACTOR: UUID = UUID(0L, 2L)

    fun step(world: World) {
        val random = world.context.random
        val faults = world.context.faults
        val accounts = world.customerAccounts
        val now = world.context.clock.instant()
        val today = LocalDate.ofInstant(now, ZONE)

        // 1. Pick the order to execute: reuse a live one, or open a new seeded one.
        val live = world.standingOrders.filter { it.status == StandingOrderStatus.ACTIVE }
        val order = when {
            live.isEmpty() || random.nextInt(NEW_ORDER_FRACTION) < NEW_ORDER_THRESHOLD ->
                createOrder(world, accounts, today, now).also(world.standingOrders::add)
            else -> live[random.nextInt(live.size)]
        }
        val index = world.standingOrders.indexOfFirst { it.id == order.id }

        val source = AccountCurrency(order.debitAccountId, world.currency)
        val targetAccount = accounts.first { it != order.debitAccountId }
        val target = AccountCurrency(targetAccount, world.currency)
        val money = Money.of(BigDecimal(order.amountMinorUnits).movePointLeft(2), world.currency)
        val amount = money.amount

        // 2. The sweep claims the execution slot BEFORE attempting money movement — the real
        //    recordExecution contract (see its KDoc: completing here used to swallow failures).
        world.standingOrders[index] = order.recordExecution(order.calculateNextDate(today), now)
        val claimed = world.standingOrders[index]

        // 3. Reserve funds. A floor breach is a legitimate decline, not a bug.
        try {
            world.balances.put(world.balances.get(source).withReservation(amount))
        } catch (_: IllegalArgumentException) {
            recordOutcomeFailure(world, index, claimed, order.id, now)
            world.audit.append("standing order ${order.id} declined: insufficient funds")
            return
        }

        // 4. Post the balanced double-entry journal as the real aggregate.
        val entry: JournalEntry
        try {
            if (faults.shouldFailWrite()) throw SimulatedWriteFailure("ledger post failed for ${order.id}")
            entry = buildTransfer(world, order, claimed, target, money)
            world.ledger.post("standing-order-${order.id}-${claimed.executionCount}", entry)
            publishBooked(world, entry)
        } catch (e: SimulatedWriteFailure) {
            // Nothing durable: release the hold and record the failed attempt. Only an ACTIVE
            // order acknowledges it; an ended recurring order may already be COMPLETED.
            world.balances.put(world.balances.get(source).releaseReservation(amount))
            recordOutcomeFailure(world, index, claimed, order.id, now)
            world.audit.append("standing order ${order.id} compensated: ${e.message}")
            return
        }

        // 5. A post-commit fault forces the same compensation AFTER the journal is durable.
        if (faults.shouldConflict()) {
            val reversal = world.ledger.reverse(
                entry.id,
                random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { random.nextUuid() },
            )
            publishBooked(world, reversal)
            world.balances.put(world.balances.get(source).releaseReservation(amount))
            recordOutcomeFailure(world, index, claimed, order.id, now)
            world.audit.append("standing order ${order.id} compensated: post-commit conflict")
            return
        }

        // 6. Happy path: release the hold and confirm. A recurring order whose endDate just
        //    passed is already COMPLETED from recordExecution (step 2), and the real consumer
        //    calls confirmExecution through runCatching — a status-require failure there is a
        //    logged warning, not a crash (StandingOrderDueConsumer) — so mirror that exactly:
        //    confirm only an order still ACTIVE, otherwise keep the recordExecution result.
        world.balances.put(world.balances.get(source).releaseReservation(amount))
        val confirmed = if (claimed.status == StandingOrderStatus.ACTIVE) claimed.confirmExecution(now) else claimed
        world.standingOrders[index] = confirmed
        world.standingOrdersBook.recordConfirmed(order.id, amount)
        world.audit.append("standing order ${order.id} execution ${claimed.executionCount} confirmed")
    }

    /**
     * Record a failed execution outcome on BOTH sides independently: the book always counts the
     * attempt, and the status machine acknowledges it only when `recordFailure` applies —
     * exactly the consumer's `recordFailureSafely` (runCatching + logged warning): an order
     * `recordExecution` just COMPLETED cannot accept a failure, and the warning path must be
     * visible to the invariant, not silently dropped.
     */
    private fun recordOutcomeFailure(world: World, index: Int, claimed: StandingOrder, orderId: UUID, now: Instant) {
        world.standingOrdersBook.recordAttemptFailure(orderId)
        if (claimed.status == StandingOrderStatus.ACTIVE) {
            world.standingOrders[index] = claimed.recordFailure(now)
            world.standingOrdersBook.recordMachineAcknowledgedFailure(orderId)
        }
    }

    private fun createOrder(world: World, accounts: List<UUID>, today: LocalDate, now: Instant): StandingOrder {
        val random = world.context.random
        val debtor = accounts[random.nextInt(accounts.size)]
        // Seeded mix of rails/frequencies, weighted toward the recurring shapes the sweep
        // actually re-picks; ONCE is included because its lifecycle differs (completes on
        // confirm, never recurs).
        val frequency = when (random.nextInt(FREQUENCY_FRACTION)) {
            in 0..ONCE_THRESHOLD -> Frequency.ONCE
            else -> Frequency.MONTHLY
        }
        return StandingOrder(
            id = random.nextUuid(),
            idempotencyKey = "sim-standing-order-${random.nextUuid()}",
            partyId = random.nextUuid(),
            debitAccountId = debtor,
            debtorIban = "CZ65SIM${abs(debtor.hashCode()) % IBAN_CHECK_DIGITS_MODULO}".padEnd(IBAN_LENGTH, '0'),
            debtorName = "Sim Debtor",
            creditorIban =
            "CZ65SIM${abs(accounts.first { it != debtor }.hashCode()) % IBAN_CHECK_DIGITS_MODULO}"
                .padEnd(IBAN_LENGTH, '0'),
            creditorName = "Sim Creditor",
            creditorBic = null,
            amountMinorUnits = random.nextLong(1L, MAX_MINOR_UNITS),
            currency = world.currency,
            frequency = frequency,
            paymentType = PaymentType.INTERNAL,
            remittanceInfo = null,
            startDate = today,
            endDate =
            if (frequency == Frequency.ONCE) {
                null
            } else {
                today.plusMonths(random.nextLong(1, END_DATE_MONTHS_MAX))
            },
            nextExecutionDate = today,
            lastExecutionDate = null,
            executionCount = 0,
            failureCount = 0,
            status = StandingOrderStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Build the real POSTED double-entry transfer: debit debtor, credit creditor (deposit-control). */
    private fun buildTransfer(
        world: World,
        order: StandingOrder,
        claimed: StandingOrder,
        target: AccountCurrency,
        money: Money,
    ): JournalEntry {
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
            subAccountId = order.debitAccountId,
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
            subAccountId = target.accountId,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = claimed.id,
            entryDate = today,
            valueDate = today,
            description = "standing order ${order.id} execution ${claimed.executionCount}",
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
