// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.PostingEvent
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Raised when a command names a deal that does not exist. Mapped to 404. */
class DealNotFoundException(dealId: UUID) : NoSuchElementException("deal $dealId not found")

/** Raised when a deal names a counterparty not in the master. Mapped to 400 via IllegalArgumentException. */
class UnknownCounterpartyException(id: String) : IllegalArgumentException("unknown counterparty '$id'")

/** A journal the ledger accepted for one deal event. */
data class LedgerJournalRef(
    val dealId: UUID,
    val event: PostingEvent,
    val idempotencyKey: String,
    val journalId: UUID,
    val postedAt: Instant,
)

/**
 * A client's `Idempotency-Key` for one command, stored with the state change it produced (same
 * transaction). A replay of the key returns the deal as it now stands instead of acting twice.
 */
data class CommandKey(val key: String, val action: String, val dealId: UUID)

/** An outbox event to commit with the deal row. */
data class DealEvent(val eventType: String, val payload: String)

interface DealRepository {
    /**
     * Persist the deal (row + new timeline entries), an optional ledger journal reference and an
     * optional outbox event in ONE transaction (ADR-0003).
     */
    suspend fun save(
        deal: Deal,
        journal: LedgerJournalRef? = null,
        event: DealEvent? = null,
        command: CommandKey? = null,
    ): Deal

    /** The command previously recorded under [key], if any. */
    suspend fun findCommand(key: String): CommandKey?

    suspend fun findById(dealId: UUID): Deal?

    suspend fun list(state: DealState?): List<Deal>

    /** Deals due for the simulated market: BOOKED with valueDate <= today, SETTLED with maturity <= today. */
    suspend fun dueForSettlement(today: LocalDate): List<Deal>

    suspend fun dueForMaturity(today: LocalDate): List<Deal>

    /** Outstanding placed principal with [counterpartyId] in [currency], excluding [excludeDealId]. */
    suspend fun exposure(counterpartyId: String, currency: String, excludeDealId: UUID?): BigDecimal

    suspend fun journals(dealId: UUID): List<LedgerJournalRef>
}

interface CounterpartyRepository {
    suspend fun findById(id: String): Counterparty?

    suspend fun list(): List<Counterparty>
}

/**
 * The ledger's journal API (`POST /api/v1/journals`, ADR-0315 D5). The treasury service never
 * writes the ledger's database. Must be idempotent on [JournalSpec.idempotencyKey].
 */
interface LedgerPostingPort {
    /** Returns the ledger's journal id — the original one on an idempotent replay. */
    suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): UUID
}

/** This service's outbox table; `persistInTransaction` chains inside the aggregate's transaction. */
interface TreasuryOutboxRepository : OutboxRepository {
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
