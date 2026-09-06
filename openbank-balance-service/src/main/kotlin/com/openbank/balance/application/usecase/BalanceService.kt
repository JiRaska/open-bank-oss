// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.`in`.*
import com.openbank.balance.application.port.out.*
import com.openbank.balance.domain.model.*
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.domain.event.EventActor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class BalanceNotFoundException(msg: String) : RuntimeException(msg)
class InsufficientFundsException(msg: String) : RuntimeException(msg)
class HoldNotFoundException(msg: String) : RuntimeException(msg)

private const val SQLSTATE_UNIQUE_VIOLATION = "23505"
private const val HOLD_REFERENCE_CONSTRAINT = "uq_balance_holds_reference"

@ApplicationScoped
class BalanceService(
    private val balanceRepo: BalanceRepository,
    private val holdRepo: HoldRepository,
    private val movementPort: BalanceMovementPort,
    private val clock: Clock,
    private val accountingClock: AccountingClock = AccountingClock.bank(clock),
) : BalanceUseCase {

    // CDI entry point: injects the production UTC clock. Tests use the primary constructor with a
    // fixed Clock for deterministic timestamps (ADR-0100 Layer 1) — and get the matching accounting
    // clock for free from the default above, so a fixed Clock fixes the accounting day too.
    //
    // No BalanceEventPublisher here since #8510: every event this use case emits is written by the
    // repository layer in the same transaction as the state change (HoldRepository.saveWithEvent /
    // releaseWithEvent, BalanceMovementPort), so a service-level publisher would be a dual write.
    @Inject
    constructor(
        balanceRepo: BalanceRepository,
        holdRepo: HoldRepository,
        movementPort: BalanceMovementPort,
    ) : this(
        balanceRepo,
        holdRepo,
        movementPort,
        Clock.systemUTC(),
    )

    /**
     * Attach the not-yet-effective credit tail (#1745). Read per call from the dated projection
     * audit against the current accounting day, so the figure becomes correct on its own the moment
     * the day passes the value date — no roll job has to have run.
     */
    /**
     * True when the failure chain carries the unique violation of `uq_balance_holds_reference`
     * (V10). Hibernate Reactive adapts the Vert.x PgException into a plain [SQLException] whose
     * sqlState may or may not survive the adaptation, so the check accepts either the 23505
     * sqlState or the "(23505)" marker the server embeds in the message text — and ALWAYS requires
     * the constraint name, so an unrelated unique violation is never swallowed as a dedup replay
     * (same shape as NotificationConsumer.isDeduplicationConflict, #8953).
     */
    private fun Throwable.isHoldReferenceConflict(): Boolean = generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any {
            it.message?.contains(HOLD_REFERENCE_CONSTRAINT) == true &&
                (it.sqlState == SQLSTATE_UNIQUE_VIOLATION || it.message.orEmpty().contains("(23505)"))
        }

    private suspend fun withValueDateBasis(balance: Balance): Balance = balance.copy(
        notYetEffectiveCredit = balanceRepo.sumNotYetEffectiveCredit(
            balance.accountId,
            balance.currency,
            accountingClock.today(),
        ),
    )

    override suspend fun getBalance(query: GetBalanceQuery): Balance {
        val currency = query.currency ?: "CZK"
        val current = balanceRepo.findByAccountIdAndCurrency(query.accountId, currency)
            ?: throw BalanceNotFoundException("Balance not found for account=${query.accountId} currency=$currency")

        // Live read: carry the value-date basis so `effectiveAvailable` excludes a posted-but-not-yet-
        // effective credit. The point-in-time branch below needs no such treatment — it rewinds the
        // whole future tail explicitly, so adding this on top would subtract the credit twice.
        val asOf = query.asOf ?: return withValueDateBasis(current)

        // Point-in-time (ADR-0039): rewind the current booked balance by the deltas booked strictly
        // after `asOf`, read from the dated ledger-projection audit. Holds are current-state and have
        // no historical meaning, so a point-in-time snapshot carries no reservation/pending and reports
        // available == booked. With the projection ledger empty (projection disabled, or no movement
        // after asOf) the future sum is ZERO and this returns the current booked figure unchanged.
        val futureDelta = balanceRepo.sumBookedDeltaAfter(query.accountId, currency, asOf)
        val bookedAsOf = current.bookedAmount - futureDelta
        return current.copy(
            bookedAmount = bookedAsOf,
            availableAmount = bookedAsOf,
            reservedAmount = java.math.BigDecimal.ZERO,
            pendingAmount = java.math.BigDecimal.ZERO,
        )
    }

    override suspend fun getBalances(accountId: UUID): List<Balance> =
        balanceRepo.findAllByAccountId(accountId).map { withValueDateBasis(it) }

    override suspend fun placeHold(cmd: PlaceHoldCommand): BalanceHold {
        // Idempotent replay (ADR-0287, #8351): the referenceId names one durable business fact, so a
        // retried placeHold with the same (accountId, currency, referenceId) replays the ORIGINAL
        // hold with no second reservation and no second event. The check runs BEFORE the balance
        // guard on purpose: a replay arriving after funds moved must still return the original hold,
        // not fail with insufficient funds. `uq_balance_holds_reference` (V10) is the race backstop.
        holdRepo.findByNaturalKey(cmd.accountId, cmd.currency, cmd.referenceId)?.let { return it }

        // The cover decision (#1745). Hydrating the value-date basis here is what actually stops a
        // posted-but-not-yet-effective credit being spent: `withReservation` guards on
        // `effectiveAvailable()`, and without this the tail is ZERO and the guard sees the raw
        // receipt-dated figure — which is the current, defective behaviour.
        val balance = withValueDateBasis(
            balanceRepo.findByAccountIdAndCurrency(cmd.accountId, cmd.currency)
                ?: throw BalanceNotFoundException("Balance not found for account=${cmd.accountId}"),
        )

        val updated = try {
            balance.withReservation(cmd.amount).copy(updatedAt = OffsetDateTime.now(clock))
        } catch (e: IllegalArgumentException) {
            throw InsufficientFundsException(e.message ?: "Insufficient funds")
        }

        val hold = BalanceHold(
            id = UUID.randomUUID(),
            accountId = cmd.accountId,
            amount = cmd.amount,
            currency = cmd.currency,
            reason = cmd.reason,
            referenceId = cmd.referenceId,
            expiresAt = cmd.ttlSeconds?.let { OffsetDateTime.now(clock).plusSeconds(it) },
            createdAt = OffsetDateTime.now(clock),
            releasedAt = null,
        )
        // Transactional outbox (#8510): the balance reservation, the hold row and the HOLD_PLACED
        // event commit in ONE transaction — no event is written for a hold that never landed, and
        // no hold lands without its event.
        return try {
            holdRepo.saveWithEvent(
                hold,
                updated,
                BalanceEvent(
                    eventId = UUID.randomUUID(),
                    eventType = BalanceEventType.HOLD_PLACED,
                    accountId = cmd.accountId,
                    currency = cmd.currency,
                    amount = cmd.amount,
                    bookedAmount = updated.bookedAmount,
                    availableAmount = updated.availableAmount,
                    reservedAmount = updated.reservedAmount,
                    occurredAt = OffsetDateTime.now(clock),
                    actorId = BalanceEventActors.API,
                    actorType = EventActor.TYPE_SYSTEM,
                    sourceService = "balance-service",
                ),
            )
        } catch (e: Exception) {
            // Lost the race against a concurrent first attempt with the same natural key: the
            // unique index rejected our insert, so the winner's row IS the correct replay answer.
            if (!e.isHoldReferenceConflict()) throw e
            holdRepo.findByNaturalKey(cmd.accountId, cmd.currency, cmd.referenceId) ?: throw e
        }
    }

    override suspend fun releaseHold(cmd: ReleaseHoldCommand): BalanceHold {
        val hold = holdRepo.findById(cmd.holdId)
            ?: throw HoldNotFoundException("Hold ${cmd.holdId} not found")

        val balance = balanceRepo.findByAccountIdAndCurrency(hold.accountId, hold.currency)
            ?: throw BalanceNotFoundException("Balance not found")

        val updated = balance.releaseReservation(hold.amount).copy(updatedAt = OffsetDateTime.now(clock))
        val released = hold.copy(releasedAt = OffsetDateTime.now(clock))

        // Transactional outbox (#8510): release + balance + HOLD_RELEASED in ONE transaction.
        holdRepo.releaseWithEvent(
            released,
            updated,
            BalanceEvent(
                eventId = UUID.randomUUID(),
                eventType = BalanceEventType.HOLD_RELEASED,
                accountId = hold.accountId,
                currency = hold.currency,
                amount = hold.amount,
                bookedAmount = updated.bookedAmount,
                availableAmount = updated.availableAmount,
                reservedAmount = updated.reservedAmount,
                occurredAt = OffsetDateTime.now(clock),
                actorId = BalanceEventActors.API,
                actorType = EventActor.TYPE_SYSTEM,
                sourceService = "balance-service",
            ),
        )

        return released
    }

    override suspend fun credit(cmd: CreditAccountCommand): Balance {
        // Idempotent: a retried credit with the same referenceId returns the same balance and is NOT
        // re-applied. The BALANCE_UPDATED outbox row is written by the port impl inside the mutation's
        // own transaction (#8510), and only on the first application — a replay writes nothing, so it
        // never double-counts in downstream projections either.
        return movementPort.applyCredit(
            cmd.accountId,
            cmd.currency,
            cmd.referenceId,
            cmd.amount,
            BalanceEventActors.API,
        ).balance
    }

    override suspend fun debit(cmd: DebitAccountCommand): Balance {
        // Idempotent (see credit). The overdraft guard runs only on the first application; a duplicate
        // returns the already-debited balance without re-checking funds or writing a second event.
        return try {
            movementPort.applyDebit(
                cmd.accountId,
                cmd.currency,
                cmd.referenceId,
                cmd.amount,
                BalanceEventActors.API,
            ).balance
        } catch (e: IllegalArgumentException) {
            throw InsufficientFundsException(e.message ?: "Insufficient funds")
        }
    }

    override suspend fun initializeBalance(cmd: InitializeBalanceCommand): Balance {
        val existing = balanceRepo.findByAccountIdAndCurrency(cmd.accountId, cmd.currency)
        if (existing != null) return existing

        val balance = Balance(
            id = UUID.randomUUID(),
            accountId = cmd.accountId,
            currency = cmd.currency,
            bookedAmount = cmd.initialAmount,
            availableAmount = cmd.initialAmount,
            reservedAmount = java.math.BigDecimal.ZERO,
            pendingAmount = java.math.BigDecimal.ZERO,
            updatedAt = OffsetDateTime.now(clock),
            version = 0,
            arrangedOverdraftLimit = cmd.arrangedOverdraftLimit,
        )
        return balanceRepo.save(balance)
    }

    override suspend fun setOverdraftLimit(cmd: SetOverdraftLimitCommand): Balance {
        val balance = balanceRepo.findByAccountIdAndCurrency(cmd.accountId, cmd.currency)
            ?: throw BalanceNotFoundException("Balance not found for account=${cmd.accountId} currency=${cmd.currency}")

        val updated = balance.copy(
            arrangedOverdraftLimit = cmd.arrangedOverdraftLimit,
            updatedAt = OffsetDateTime.now(clock),
            version = balance.version + 1,
        )
        return balanceRepo.update(updated)
    }
}
