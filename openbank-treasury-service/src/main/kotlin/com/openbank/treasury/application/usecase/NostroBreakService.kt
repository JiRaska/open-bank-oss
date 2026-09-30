// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.treasury.application.port.`in`.NostroBreakSweep
import com.openbank.treasury.application.port.`in`.NostroBreakUseCase
import com.openbank.treasury.application.port.`in`.NostroBreakView
import com.openbank.treasury.application.port.`in`.NostroReconciliationUseCase
import com.openbank.treasury.application.port.out.NostroAccountNotFoundException
import com.openbank.treasury.application.port.out.NostroBreakRepository
import com.openbank.treasury.application.port.out.NostroStatementRepository
import com.openbank.treasury.application.port.out.StatementNotFoundException
import com.openbank.treasury.domain.model.BreakAlertPolicy
import com.openbank.treasury.domain.model.BreakChanges
import com.openbank.treasury.domain.model.NostroBreakAged
import com.openbank.treasury.domain.model.NostroBreakBook
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Nostro reconciliation breaks (ADR-0315 D7): what a reconciliation leaves unmatched is recorded
 * with the day it was first seen, ages in business days, resolves when a later reconciliation
 * matches it, and alerts ONCE when it passes [policy]. Nothing here posts to the ledger — a break
 * is for a person to resolve.
 */
class NostroBreakService(
    private val statements: NostroStatementRepository,
    private val reconciliation: NostroReconciliationUseCase,
    private val breakRepo: NostroBreakRepository,
    accounts: Map<String, String>,
    override val policy: BreakAlertPolicy,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : NostroBreakUseCase {

    private val ibans: Set<String> = accounts.keys.map { normalize(it) }.toSet()

    override suspend fun observe(statementId: UUID): BreakChanges {
        val stored = statements.findById(statementId) ?: throw StatementNotFoundException(statementId)
        val r = reconciliation.reconcile(statementId)
        val s = stored.statement
        val existing = breakRepo.inScope(stored.glCode, statementId, s.firstDate, s.lastDate)
        val changes = NostroBreakBook.observe(r, statementId, existing, LocalDate.now(clock), Ids::newId)
        if (!changes.isEmpty) breakRepo.apply(changes)
        return changes
    }

    override suspend fun sweep(today: LocalDate): NostroBreakSweep {
        val failures = mutableListOf<Throwable>()
        val ids = statements.statementIdsSince(today.minusDays(LOOKBACK_DAYS))
        ids.forEach { id ->
            try {
                observe(id)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // One statement the ledger cannot answer for must not stop the others being aged.
                failures += e
            }
        }
        val open = breakRepo.open()
        val aged = open.filter { policy.isAged(it, today) }
        var alerted = 0
        aged.filter { it.alertedAt == null }.forEach { b ->
            val now = Instant.now(clock)
            val payload = NostroBreakAged(
                breakId = b.id,
                iban = b.iban,
                glCode = b.glCode,
                currency = b.currency,
                side = b.side,
                ourSide = b.ourSide,
                amount = b.amount,
                bookingDate = b.bookingDate,
                firstSeenOn = b.firstSeenOn,
                ageBusinessDays = b.ageBusinessDays(today),
                thresholdDays = policy.ageDays,
                occurredAt = now,
            )
            if (breakRepo.markAlerted(
                    b.id,
                    now,
                    NostroBreakAged.EVENT_TYPE,
                    objectMapper.writeValueAsString(payload),
                )
            ) {
                alerted++
            }
        }
        return NostroBreakSweep(ids.size, failures, open.size, aged.size, alerted)
    }

    override suspend fun breaks(iban: String, includeResolved: Boolean): List<NostroBreakView> {
        val account = normalize(iban)
        if (account !in ibans) throw NostroAccountNotFoundException(account)
        val today = LocalDate.now(clock)
        return breakRepo.byIban(account, includeResolved)
            .sortedWith(compareBy({ it.firstSeenOn }, { it.bookingDate }, { it.breakKey }))
            .map { NostroBreakView(it, it.ageBusinessDays(today), policy.isAged(it, today)) }
    }

    private fun normalize(iban: String) = iban.replace(" ", "").uppercase()

    private companion object {
        /**
         * Statements closing within this many days are re-reconciled on every sweep, so a ledger
         * line booked late resolves the break it caused. Older open breaks keep ageing untouched.
         */
        const val LOOKBACK_DAYS = 31L
    }
}
