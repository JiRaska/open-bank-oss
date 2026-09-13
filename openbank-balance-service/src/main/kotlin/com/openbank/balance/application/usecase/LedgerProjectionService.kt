// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.`in`.AccountBookedChange
import com.openbank.balance.application.port.`in`.LedgerProjectionUseCase
import com.openbank.balance.application.port.out.LedgerProjectionPort
import com.openbank.balance.domain.model.BalanceEventActors
import com.openbank.libs.observability.DomainMetrics
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * ADR-0039 Phase D: projects ledger `AccountBookedChanged` events onto the balance read-model.
 *
 * The ledger is the golden source of booked money; this service makes the balance a pure projection
 * of it. Each booked delta is applied exactly once (dedup in [LedgerProjectionPort], same transaction
 * as the balance write). In the same transaction it consumes this pocket's originating payment cover
 * hold (referenceId == transactionId) — this is what lets the payment saga stop debiting balance
 * directly (Phase D-2) without opening an overspend window between hold-release and projection.
 */
@ApplicationScoped
class LedgerProjectionService(private val projectionPort: LedgerProjectionPort, private val metrics: DomainMetrics) :
    LedgerProjectionUseCase {

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
            // The port also consumes matching cover in its transaction, including an old
            // partial projection whose marker committed before its cover was released.
            log.debugf(
                "Skipping already-applied booked delta journalEntry=%s account=%s currency=%s",
                change.journalEntryId,
                change.accountId,
                change.currency,
            )
        }

        if (applied != null) {
            // ADR-0077 Tier C: count each revaluation (booked delta from ledger projection).
            metrics.balanceRevaluated(change.currency)
        }
    }
}
