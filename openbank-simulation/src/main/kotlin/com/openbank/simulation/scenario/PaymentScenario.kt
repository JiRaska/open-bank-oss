// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.simulation.scenario

import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.adapters.AccountBookedChanged
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.SimPaymentSaga
import com.openbank.simulation.runner.World
import com.openbank.transaction.domain.saga.PaymentSaga
import com.openbank.transaction.domain.saga.SagaState
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One step of the money path: a single internal transfer driven through the payment saga,
 * faithful to `PaymentSagaOrchestrator` — reserve funds on the source, post a balanced
 * double-entry journal **as the real `openbank-ledger-service` `JournalEntry` aggregate**,
 * project the booked movement onto balances via the real `bookedDeltas()`, release the hold;
 * and on an injected fault, compensate (reverse the journal via the real `reverse()`, release
 * the hold) so the saga always reaches a terminal state.
 *
 * Every choice (accounts, amount, which faults fire) is drawn from the run's seeded RNG, so a
 * step is a pure function of the seed and the step index. (The real `JournalEntry.reverse()` no
 * longer reads the wall clock — its booking time is now injected by the application layer, ADR-0100
 * Layer 1; it still mints random line ids, a separate ADR-0106 concern — but neither affects the
 * booked money math the invariants assert, so the run's verdict stays reproducible from the seed.)
 */
object PaymentScenario {

    private const val MAX_MINOR_UNITS = 500_000L // up to 5000.00 in a 2-dp currency
    private val ZONE = ZoneId.of("Europe/Prague")

    // A single shared deposit-control GL account (e.g. "2100 CZK"); the customer dimension is
    // carried by JournalLine.subAccountId, which is what bookedDeltas() projects on.
    private val DEPOSIT_CONTROL_GL: UUID = UUID(0L, 1L)
    private val SYSTEM_ACTOR: UUID = UUID(0L, 2L)

    fun step(world: World) {
        val random = world.context.random
        val faults = world.context.faults
        val accounts = world.customerAccounts

        val amount = BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2)
        val money = Money.of(amount, world.currency)

        val sourceIdx = random.nextInt(accounts.size)
        val targetIdx = (sourceIdx + 1 + random.nextInt(accounts.size - 1)) % accounts.size
        val source = AccountCurrency(accounts[sourceIdx], world.currency)
        val target = AccountCurrency(accounts[targetIdx], world.currency)

        val index = world.sagas.size
        val clock = world.context.clock
        var saga = SimPaymentSaga(
            saga = PaymentSaga.start(random.nextUuid(), "sim-$index", clock, id = random.nextUuid()),
            sourceAccount = source,
        )
        world.sagas.add(saga)

        fun advance(to: SagaState) {
            saga = saga.copy(saga = saga.saga.transitionTo(to, clock))
            world.sagas[index] = saga
            world.audit.append("saga ${saga.id} -> ${saga.state}")
        }

        advance(SagaState.PAYMENT_INITIATED)

        // 1. Reserve funds on the source. A floor breach is a legitimate decline, not a bug.
        try {
            world.balances.put(world.balances.get(source).withReservation(amount))
            saga = saga.copy(reservedAmount = amount)
            advance(SagaState.FUNDS_RESERVED)
        } catch (_: IllegalArgumentException) {
            advance(SagaState.FAILED)
            return
        }

        // 2. Post the balanced double-entry journal. An injected write fault here means the
        //    ledger call failed before committing — nothing posted, so we just compensate.
        advance(SagaState.LEDGER_POSTING)
        try {
            if (faults.shouldFailWrite()) throw SimulatedWriteFailure("ledger post failed for ${saga.id}")
            val entry = buildTransfer(world, saga, target, money)
            world.ledger.post("saga-${saga.id}-ledger", entry)
            saga = saga.copy(journalId = entry.id)
            publishBooked(world, entry)
        } catch (e: SimulatedWriteFailure) {
            compensate(world, ::advance, saga, e.message)
            return
        }

        // 3. A post-commit fault forces compensation AFTER the journal is durable: the saga
        //    reverses the posting and releases the hold (money conserved end-to-end).
        if (faults.shouldConflict()) {
            compensate(world, ::advance, saga, "post-commit conflict")
            return
        }

        // 4. Happy path: release the hold and complete.
        releaseHold(world, source, amount)
        advance(SagaState.COMPLETED)
    }

    /** Build the real POSTED double-entry transfer: debit source, credit target (deposit-control). */
    private fun buildTransfer(world: World, saga: SimPaymentSaga, target: AccountCurrency, money: Money): JournalEntry {
        val journalId = world.context.random.nextUuid()
        val source = saga.sourceAccount ?: error("transfer saga must carry a source account")
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
            subAccountId = source.accountId,
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
            transactionId = saga.saga.transactionId,
            entryDate = today,
            valueDate = today,
            description = "transfer $journalId",
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    private fun lineId(journalId: UUID, sequence: Long): UUID = UUID(journalId.mostSignificantBits, sequence)

    private fun compensate(world: World, advance: (SagaState) -> Unit, saga: SimPaymentSaga, reason: String?) {
        advance(SagaState.COMPENSATING)
        val journalId = saga.journalId
        if (journalId != null) {
            // Real JournalEntry.reverse() flips every side; re-project so balances unwind too.
            val reversal = world.ledger.reverse(
                journalId,
                world.context.random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { world.context.random.nextUuid() },
            )
            publishBooked(world, reversal)
        }
        // Release the hold placed on the source at FUNDS_RESERVED (both are carried on the saga).
        val source = saga.sourceAccount
        val amount = saga.reservedAmount
        if (source != null && amount != null) {
            releaseHold(world, source, amount)
        }
        world.audit.append("saga ${saga.id} compensated: $reason")
        advance(SagaState.COMPENSATED)
    }

    /** Project the entry's real `bookedDeltas()` (credit-positive, deposit-control) onto the bus. */
    private fun publishBooked(world: World, entry: JournalEntry) {
        entry.bookedDeltas().forEach { delta ->
            world.bus.publish(
                AccountBookedChanged(entry.id, AccountCurrency(delta.accountId, delta.currency), delta.delta),
            )
        }
    }

    private fun releaseHold(world: World, source: AccountCurrency, amount: BigDecimal) {
        world.balances.put(world.balances.get(source).releaseReservation(amount))
    }
}
