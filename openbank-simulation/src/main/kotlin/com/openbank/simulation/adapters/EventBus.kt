// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.adapters

import com.openbank.simulation.engine.DeterministicScheduler
import com.openbank.simulation.engine.FaultInjector
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.BalanceStore
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/**
 * The ledger→balance projection event (`openbank-balance-service` `AccountBookedChanged`,
 * ADR-0039 Phase D): a credit-positive booked movement on one account from one journal.
 */
data class AccountBookedChanged(val journalId: UUID, val account: AccountCurrency, val delta: BigDecimal)

/**
 * The balance-service projection consumer. Production guards against at-least-once
 * re-delivery with a dedup marker on `(journalId, account)` so a duplicated event is a
 * no-op (`LedgerProjectionPort.applyBookedDelta` returns null on a duplicate). [dedupEnabled]
 * lets a scenario inject the realistic idempotency-gap bug — turning it off lets duplicates
 * double-apply, which the DST invariants then catch.
 */
class BalanceProjection(private val balances: BalanceStore, private val dedupEnabled: Boolean = true) {
    private val applied = mutableSetOf<Pair<UUID, AccountCurrency>>()

    fun on(event: AccountBookedChanged) {
        // The projection only tracks customer balances; bank/clearing GL accounts have none.
        if (!balances.has(event.account)) return
        val marker = event.journalId to event.account
        if (dedupEnabled && !applied.add(marker)) return
        balances.put(balances.get(event.account).applyBookedDelta(event.delta))
    }
}

/**
 * A simulated message bus with at-least-once delivery. Faults are realised through the
 * deterministic scheduler, never through real wall-clock races:
 *  - **duplicate** — the event is delivered twice;
 *  - **drop** — delivery is deferred and retried (outbox redelivery), never lost — so a
 *    correct, dedup-guarded consumer always converges;
 *  - **reorder** — a larger virtual delay lets a later event overtake an earlier one.
 */
class SimEventBus(
    private val scheduler: DeterministicScheduler,
    private val faults: FaultInjector,
    private val projection: BalanceProjection,
) {
    private val normalDelay = Duration.ofMillis(NORMAL_DELAY_MS)
    private val reorderDelay = Duration.ofMillis(REORDER_DELAY_MS)
    private val redeliveryDelay = Duration.ofMillis(REDELIVERY_DELAY_MS)

    /**
     * [onApplied], when given, runs INSIDE the same deferred delivery task, right after the
     * projection applies [event]'s delta — never before. A caller that holds a synchronous
     * reservation on this account (e.g. [com.openbank.simulation.scenario.PaymentScenario]'s
     * funds hold) passes its release here instead of releasing right after `publish()` returns:
     * releasing eagerly would restore `available` before this delta actually lands (delivery is
     * always deferred onto the scheduler, drained once per step after every scenario has run),
     * a same-step window where another scenario reading `available()` sees room that is really
     * already spoken for by this not-yet-applied debit (ADR-0100 seed 110 regression: exactly
     * this race let a fee charge and an in-flight payment debit jointly breach the floor).
     * Fires on every delivery (including a duplicate), so [onApplied] must itself be idempotent.
     */
    fun publish(event: AccountBookedChanged, onApplied: (() -> Unit)? = null) {
        val firstDelay = when {
            faults.shouldDropEvent() -> redeliveryDelay
            faults.shouldReorderEvent() -> reorderDelay
            else -> normalDelay
        }
        scheduler.schedule(firstDelay) {
            projection.on(event)
            onApplied?.invoke()
        }
        if (faults.shouldDuplicateEvent()) {
            scheduler.schedule(redeliveryDelay) {
                projection.on(event)
                onApplied?.invoke()
            }
        }
    }

    private companion object {
        // Virtual-time delivery delays (ms). Their ORDER, not the absolute values, is what
        // realises duplicate/reorder/redelivery against the deterministic scheduler.
        const val NORMAL_DELAY_MS = 10L
        const val REORDER_DELAY_MS = 50L
        const val REDELIVERY_DELAY_MS = 200L
    }
}
