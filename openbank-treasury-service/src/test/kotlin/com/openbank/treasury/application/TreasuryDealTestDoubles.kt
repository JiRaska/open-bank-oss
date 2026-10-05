// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.openbank.libs.persistence.lock.ClusterLock
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.application.port.out.PendingFundingPort
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.LedgerNostroLine
import com.openbank.treasury.domain.model.ProductType
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** In-memory test doubles shared by the treasury deal service tests. */
internal class RecordingLedger : LedgerPostingPort {
    val posted = mutableListOf<JournalSpec>()
    val entryDates = mutableMapOf<String, LocalDate>()
    var failFor: UUID? = null
    override suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): UUID {
        if (spec.dealId == failFor) error("ledger unavailable")
        posted += spec
        entryDates[spec.idempotencyKey] = entryDate
        return UUID.nameUUIDFromBytes(spec.idempotencyKey.toByteArray())
    }
}

internal class RecordingFundingRead : LedgerReadPort {
    val balances = mutableMapOf<Triple<String, String, LocalDate>, BigDecimal>()
    override suspend fun nostroLines(glCode: String, from: LocalDate, to: LocalDate): List<LedgerNostroLine> =
        emptyList()
    override suspend fun accountBalance(glCode: String, currency: String, asOf: LocalDate): BigDecimal? =
        balances[Triple(glCode, currency, asOf)] ?: BigDecimal("1000000000000.00")
}

internal val ALWAYS_FUNDING_LOCK = object : ClusterLock {
    override suspend fun <T> tryRunExclusively(jobName: String, block: suspend () -> T): T = block()
}

internal class InMemoryDeals :
    DealRepository,
    PendingFundingPort {
    val rows = linkedMapOf<UUID, Deal>()
    val journals = mutableListOf<LedgerJournalRef>()
    val events = mutableListOf<DealEvent>()

    val commands = mutableMapOf<String, CommandKey>()

    override suspend fun save(deal: Deal, journal: LedgerJournalRef?, event: DealEvent?, command: CommandKey?): Deal {
        rows[deal.id] = deal
        journal?.let { journals += it }
        event?.let { events += it }
        command?.let { commands[it.key] = it }
        return deal
    }

    override suspend fun findCommand(key: String) = commands[key]

    override suspend fun findById(dealId: UUID) = rows[dealId]
    override suspend fun list(state: DealState?) = rows.values.filter { state == null || it.state == state }
    override suspend fun pendingPlacements(currency: String) = rows.values.filter {
        it.currency == currency && it.product.isAsset && it.state in setOf(DealState.BOOKED, DealState.CONFIRMED)
    }
    override suspend fun dueForSettlement(today: LocalDate, states: Set<DealState>) =
        rows.values.filter { it.state in states && !it.valueDate.isAfter(today) }
    override suspend fun dueForMaturity(today: LocalDate) = rows.values.filter {
        it.state == DealState.SETTLED && !it.maturityDate.isAfter(today) && it.product != ProductType.FX_SPOT
    }
    override suspend fun exposure(counterpartyId: String, currency: String, excludeDealId: UUID?) = rows.values.filter {
        it.counterpartyId == counterpartyId &&
            it.limitCurrency == currency &&
            it.consumesLimit &&
            it.id != excludeDealId
    }.sumOf { it.limitAmount }
    override suspend fun pendingLimitOverrides() =
        rows.values.filter { it.state == DealState.PENDING_APPROVAL && it.limitOverride != null }
    override suspend fun journals(dealId: UUID) = journals.filter { it.dealId == dealId }
    override suspend fun recordJournal(journal: LedgerJournalRef) {
        if (journals.none { it.idempotencyKey == journal.idempotencyKey }) journals += journal
    }
}
