// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.port.out

import com.openbank.balance.domain.model.Balance
import com.openbank.balance.domain.model.BalanceEvent
import com.openbank.balance.domain.model.BalanceHold
import java.math.BigDecimal
import java.util.UUID

/** Outbound persistence port for the per-account, per-currency balance aggregate. */
interface BalanceRepository {

    suspend fun findByAccountIdAndCurrency(accountId: UUID, currency: String): Balance?

    suspend fun findAllByAccountId(accountId: UUID): List<Balance>

    suspend fun save(balance: Balance): Balance

    suspend fun update(balance: Balance): Balance

    /** Sum of booked amounts across all accounts, grouped by currency (ADR-0039 reconciliation). */
    suspend fun sumBookedByCurrency(): Map<String, BigDecimal>

    /**
     * Booked sub-ledger total per currency **on the ledger's value-date basis** as of [asOf], for the
     * reconciliation tie-out (ADR-0178). Computed as the materialized booked balance
     * ([sumBookedByCurrency]) minus the future-value-dated tail — the projected deltas whose
     * `entry_date > asOf` — so it mirrors the ledger trial balance's `entry_date <= :asOf` and a
     * future-value-dated journal is excluded on both sides until its value date. Anchoring on the
     * materialized `balances` (not on the audit sum) preserves the integrity coverage of the plain
     * aggregate tie-out: a write-path bug that desynchronized `balances` from the projection audit
     * still shows as drift rather than being hidden. Equals [sumBookedByCurrency] when no movement is
     * value-dated after [asOf].
     */
    suspend fun sumBookedByCurrencyAsOf(asOf: java.time.LocalDate): Map<String, BigDecimal>

    /**
     * The future-value-dated **pipeline** per currency: Σ of projected booked deltas whose value date
     * is strictly after [asOf], read from the dated projection audit (`ledger_projection_event`).
     *
     * This is exactly the tail [sumBookedByCurrencyAsOf] subtracts, published in its own right for
     * ADR-0178 Phase 3 control quality: it is the expected upcoming movement, so an operator reading
     * the tie-out can see *why* the value-date sub-ledger sum differs from the raw materialized booked
     * total rather than facing an unexplained control-account gap. Read-only reporting — it moves no
     * money and does not feed the drift calculation (both tie-out sides already exclude this tail, so
     * subtracting it again would double-count). Empty when nothing is value-dated after [asOf].
     */
    suspend fun sumFutureValueDatedByCurrency(asOf: java.time.LocalDate): Map<String, BigDecimal>

    /**
     * Sum of booked deltas applied to ([accountId], [currency]) with a booking date *strictly after*
     * [asOf], read from the dated ledger-projection audit (`ledger_projection_event`). Used to rewind
     * the current booked balance to a point in time: `bookedAsOf = current − sumBookedDeltaAfter(asOf)`.
     * Returns ZERO when the projection ledger holds no later movements (e.g. projection disabled, or a
     * period with no activity after [asOf]) — so the rewind degrades to the current balance, never errors.
     */
    suspend fun sumBookedDeltaAfter(accountId: UUID, currency: String, asOf: java.time.LocalDate): BigDecimal

    /**
     * The not-yet-effective **credit** tail for one ([accountId], [currency]): Σ of projected booked
     * deltas that are strictly positive and value-dated strictly after [asOf] (ADR-0178 Phase 2,
     * #1745). Feeds [com.openbank.balance.domain.model.Balance.notYetEffectiveCredit], which the
     * cover decision subtracts so a posted-but-not-yet-effective credit cannot be spent.
     *
     * Strictly positive deltas only — deliberately NOT the net tail. Netting would add future-dated
     * debits back into the spendable figure; see `Balance.effectiveAvailable` for why that is the
     * unsafe direction. Returns ZERO when nothing is value-dated after [asOf], which is the
     * overwhelmingly common case and makes this a no-op for every account without a forward-dated
     * credit.
     */
    suspend fun sumNotYetEffectiveCredit(accountId: UUID, currency: String, asOf: java.time.LocalDate): BigDecimal

    /**
     * The ([accountId], [currency]) pairs holding at least one strictly-positive projected delta
     * value-dated exactly on [date] — i.e. the credits that *become* effective on that accounting
     * day. Drives the daily value-date roll's maturity announcement (#1745); it reads the derived
     * figure and mutates nothing, so a skipped run costs a notification, never a balance.
     */
    suspend fun findCreditsMaturingOn(date: java.time.LocalDate): List<AccountCurrency>
}

/** A balance key: the ([accountId], [currency]) pair a projected delta moved. */
data class AccountCurrency(val accountId: UUID, val currency: String)

/** Outbound persistence port for reservations (holds) against a balance. */
interface HoldRepository {

    suspend fun findById(holdId: UUID): BalanceHold?

    suspend fun findActiveByAccountId(accountId: UUID): List<BalanceHold>

    /**
     * Active (not yet released) holds whose referenceId matches — used by the ledger projection to
     * release the cover hold of the originating payment (referenceId == transactionId) as it applies
     * the booked delta (ADR-0039 Phase D).
     */
    suspend fun findActiveByReferenceId(referenceId: String): List<BalanceHold>

    /**
     * The hold recorded for the caller-supplied natural key (accountId, currency, referenceId), or
     * null. The referenceId names one durable business fact (a payment authorisation), so a retried
     * placeHold with the same triple must replay the ORIGINAL hold, never reserve a second time
     * (ADR-0287, burn-down #8351). Backed by `uq_balance_holds_reference` (V10), which is also the
     * race backstop for two concurrent first attempts.
     */
    suspend fun findByNaturalKey(accountId: UUID, currency: String, referenceId: String): BalanceHold?

    suspend fun save(hold: BalanceHold): BalanceHold

    suspend fun update(hold: BalanceHold): BalanceHold

    /**
     * Transactional outbox (#8510): persists [hold], applies the reservation to [balance] and
     * writes the HOLD_PLACED [event] row — all in ONE transaction, so the state change and its
     * event either both commit or neither does. The event is a REQUIRED parameter: there is no
     * eventless overload to bypass.
     */
    suspend fun saveWithEvent(hold: BalanceHold, balance: Balance, event: BalanceEvent): BalanceHold

    /**
     * Transactional outbox (#8510): persists the [hold] release, applies it to [balance] and
     * writes the HOLD_RELEASED [event] row — all in ONE transaction.
     */
    suspend fun releaseWithEvent(hold: BalanceHold, balance: Balance, event: BalanceEvent): BalanceHold
}

/** Outbound port for publishing balance domain events to the broker.
 *
 *  Since #8510 this is backed by the transactional outbox (`balance_outbox` +
 *  `balance-outbox-out` channel), NOT a direct emitter: the only remaining caller is the
 *  value-date roll's announcement-only event (no state change to commit alongside), and even
 *  that now survives a crash between the decision and the emit. State-changing callers do NOT
 *  use this port at all — their event is written by the repository in the same transaction as
 *  the mutation ([HoldRepository.saveWithEvent], [BalanceMovementPort], [LedgerProjectionPort]). */
interface BalanceEventPublisher {

    suspend fun publish(event: BalanceEvent)
}

/**
 * Outbound port for the ledger projection (ADR-0039 Phase D). Applies a signed booked delta to the
 * projected balance and records the dedup marker in the SAME transaction, so an at-least-once
 * redelivery of the same ledger event can never double-apply the movement.
 */
interface LedgerProjectionPort {

    /**
     * Atomically: record the dedup marker for ([journalEntryId], [accountId], [currency]) and apply
     * [delta] to that balance (initializing a zero balance if none exists yet — a posted accounting
     * fact must land even on an account the read-model has not seen) **and write the BALANCE_UPDATED
     * outbox row naming [actorId]** — all in the SAME transaction (#8510). Returns the updated
     * [Balance], or `null` if the marker already existed (duplicate delivery — nothing was applied
     * and no event was written).
     */
    @Suppress("LongParameterList")
    suspend fun applyBookedDelta(
        journalEntryId: UUID,
        accountId: UUID,
        currency: String,
        delta: BigDecimal,
        transactionId: UUID,
        entryDate: java.time.LocalDate,
        actorId: String,
    ): Balance?
}

/** Result of an idempotent movement: the resulting [balance] and whether THIS call actually applied
 *  it ([applied] = false means a duplicate referenceId was detected and nothing changed). */
data class MovementOutcome(val balance: Balance, val applied: Boolean)

/**
 * Outbound port for the idempotent DIRECT credit/debit path (the money movement the transaction saga
 * drives). Each call records a dedup marker for ([accountId], [currency], referenceId, operation) in
 * the SAME transaction as the balance mutation, so a retried credit/debit with the same referenceId
 * applies exactly once. The balance must already exist (a movement is not an account-opening event).
 */
interface BalanceMovementPort {

    /**
     * Idempotently credit [amount]. Returns the resulting balance and whether it was applied now.
     *
     * On the FIRST application the impl also writes the BALANCE_UPDATED outbox row inside the same
     * transaction as the dedup marker and the balance mutation (#8510) — the event names [actorId]
     * as its system origin and carries the post-mutation figures, which only the impl knows. A
     * duplicate delivery writes nothing, so a replay can never double-count downstream.
     */
    suspend fun applyCredit(
        accountId: UUID,
        currency: String,
        referenceId: String,
        amount: BigDecimal,
        actorId: String,
    ): MovementOutcome

    /**
     * Idempotently debit [amount]. Returns the resulting balance and whether it was applied now.
     * Throws [IllegalArgumentException] (overdraft guard) if the first application would breach the
     * floor — the caller maps it to an insufficient-funds error; a duplicate never re-checks/throws.
     * The event rule is [applyCredit]'s: first application writes the outbox row (with the amount
     * negated, matching the pre-#8510 wire shape), a duplicate writes nothing.
     */
    suspend fun applyDebit(
        accountId: UUID,
        currency: String,
        referenceId: String,
        amount: BigDecimal,
        actorId: String,
    ): MovementOutcome
}
