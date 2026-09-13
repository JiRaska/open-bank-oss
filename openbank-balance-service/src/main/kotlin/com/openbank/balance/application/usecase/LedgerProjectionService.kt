// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.`in`.AccountBookedChange
import com.openbank.balance.application.port.`in`.BalanceUseCase
import com.openbank.balance.application.port.`in`.LedgerProjectionUseCase
import com.openbank.balance.application.port.`in`.ReleaseHoldCommand
import com.openbank.balance.application.port.out.HoldRepository
import com.openbank.balance.application.port.out.LedgerProjectionPort
import com.openbank.balance.domain.model.BalanceEventActors
import com.openbank.libs.observability.DomainMetrics
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.UUID

/**
 * ADR-0039 Phase D: projects ledger `AccountBookedChanged` events onto the balance read-model.
 *
 * The ledger is the golden source of booked money; this service makes the balance a pure projection
 * of it. Each booked delta is applied exactly once (dedup in [LedgerProjectionPort], same transaction
 * as the balance write). As it applies a movement it also releases the originating payment's cover
 * hold (referenceId == transactionId) — this is what lets the payment saga stop debiting balance
 * directly (Phase D-2) without opening an overspend window between hold-release and projection.
 */
@ApplicationScoped
class LedgerProjectionService(
    private val projectionPort: LedgerProjectionPort,
    private val holdRepo: HoldRepository,
    private val balanceUseCase: BalanceUseCase,
    private val metrics: DomainMetrics,
) : LedgerProjectionUseCase {

    private val log = Logger.getLogger(LedgerProjectionService::class.java)

    override suspend fun apply(change: AccountBookedChange) {
        // The BALANCE_UPDATED event is written by the port impl in the SAME transaction as the
        // dedup marker and the balance mutation (#8510), only on first application — a duplicate
        // delivery applies nothing and announces nothing.
        val applied = projectionPort.applyBookedDelta(
            journalEntryId = change.journalEntryId,
            accountId = change.accountId,
            currency = change.currency,
            delta = change.delta,
            transactionId = change.transactionId,
            entryDate = change.entryDate,
            actorId = BalanceEventActors.LEDGER_PROJECTION,
        )

        if (applied == null) {
            // Duplicate delivery: balance already moved for this (journalEntry, account, currency).
            // Still attempt the hold release below — it is idempotent and self-heals a crash that
            // landed the delta but not the release.
            log.debugf(
                "Skipping already-applied booked delta journalEntry=%s account=%s currency=%s",
                change.journalEntryId,
                change.accountId,
                change.currency,
            )
        }

        releaseCoverHolds(change.transactionId)

        if (applied != null) {
            // ADR-0077 Tier C: count each revaluation (booked delta from ledger projection).
            metrics.balanceRevaluated(change.currency)
        }
    }

    // Release the cover hold(s) of the originating payment as the booked movement lands. Best-effort:
    // a failure here is backstopped by the hold TTL, so it must not fail the projection (which owns
    // the money-correct booked balance). releaseHold is idempotent on already-released holds.
    private suspend fun releaseCoverHolds(transactionId: UUID) {
        try {
            holdRepo.findActiveByReferenceId(transactionId.toString()).forEach { hold ->
                balanceUseCase.releaseHold(ReleaseHoldCommand(hold.id))
            }
        } catch (ex: Exception) {
            log.warnf(ex, "Failed to release cover hold(s) for transaction %s; relying on TTL", transactionId)
        }
    }
}
