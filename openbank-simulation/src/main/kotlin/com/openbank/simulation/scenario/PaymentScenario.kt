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
import com.openbank.simulation.model.SimPaymentSaga
import com.openbank.simulation.runner.World
import com.openbank.transaction.domain.saga.SagaState
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One step of the money path: a single internal transfer modelled as a saga execution
 * (ADR-0100 Layer 2, ADR-0120 Phase 5). Reserves funds on the source, posts a balanced
 * double-entry journal **as the real `openbank-ledger-service` `JournalEntry` aggregate**,
 * projects the booked movement onto balances via the real `bookedDeltas()`, releases the hold;
 * and on an injected fault, compensates (reverses the journal, releases the hold) so the saga
 * always reaches a terminal state.
 *
 * Every choice (accounts, amount, which faults fire) is drawn from the run's seeded RNG, so a
 * step is a pure function of the seed and the step index.
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
        var saga = SimPaymentSaga(
            id = random.nextUuid(),
            transactionId = random.nextUuid(),
            state = SagaState.STARTED,
            sourceAccount = source,
        )
        world.sagas.add(saga)

        fun advance(to: SagaState) {
            saga = saga.transitionTo(to)
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
            // The hold is released only once its own booked delta actually lands (inside
            // publishBooked, scoped to `source`) — NOT here, synchronously. World.bus defers
            // the ledger->balance projection onto the scheduler (drained once per step, after
            // every scenario in the step has run), so releasing the hold right after posting
            // would restore `available` before the real debit is applied — a same-step window
            // where this account's true available balance is overstated to any other scenario
            // that reads it (e.g. FeeBillingScenario), exactly how seed 110 drove an account to
            // -0.56 CZK: the hold was released here, the fee scenario then saw the (still
            // pre-debit) available balance as room, and only once the scheduler drained did
            // BOTH this payment's debit and the fee's debit land, together breaching the floor.
            publishBooked(world, entry, holdToRelease = source to amount)
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

        // 4. Happy path: the hold's release is already scheduled (step 2, above) to fire when
        //    the debit lands — nothing left to release synchronously here.
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
            transactionId = saga.transactionId,
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

    /**
     * Project the entry's real `bookedDeltas()` (credit-positive, deposit-control) onto the bus.
     * [holdToRelease], when given, releases that (account, amount) hold from INSIDE the same
     * deferred delivery that applies its debit leg — not before — so `available` never reflects
     * "hold released" ahead of "debit actually landed" within the step (see the `step()` comment
     * at the call site). Safe under at-least-once delivery: [releaseHold] clamps to the currently
     * reserved amount, so a duplicated delivery's extra release is a no-op.
     */
    private fun publishBooked(
        world: World,
        entry: JournalEntry,
        holdToRelease: Pair<AccountCurrency, BigDecimal>? = null,
    ) {
        entry.bookedDeltas().forEach { delta ->
            val account = AccountCurrency(delta.accountId, delta.currency)
            world.bus.publish(AccountBookedChanged(entry.id, account, delta.delta)) {
                if (holdToRelease != null && holdToRelease.first == account) {
                    releaseHold(world, holdToRelease.first, holdToRelease.second)
                }
            }
        }
    }

    private fun releaseHold(world: World, source: AccountCurrency, amount: BigDecimal) {
        world.balances.put(world.balances.get(source).releaseReservation(amount))
    }
}
