// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.OpenIntentSummary
import com.openbank.treasury.application.port.out.PostedJournal
import com.openbank.treasury.application.port.out.PostingIntent
import com.openbank.treasury.application.port.out.PostingIntentCloseOutcome
import com.openbank.treasury.application.port.out.PostingIntentPort
import com.openbank.treasury.application.port.out.PostingIntentStatus
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.PostingLine
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.ResolvedAccount
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** In-memory test doubles shared by the treasury deal service tests. */
internal class RecordingLedger : LedgerPostingPort {
    val posted = mutableListOf<JournalSpec>()
    val entryDates = mutableMapOf<String, LocalDate>()
    var failFor: UUID? = null

    /** The ledger answers 422 for this deal: a definitive refusal, no journal created. */
    var refuseFor: UUID? = null

    /** The ledger commits this deal's next journal and the answer is lost (the process dies). */
    var loseAnswerFor: UUID? = null

    /** Called before every post with its key (a test checks the intent is already recorded). */
    var beforePost: (String) -> Unit = {}

    /** Runs after every accepted post (a test advances its clock here). */
    var onPost: () -> Unit = {}

    /**
     * Bumped to simulate a reviewed ledger migration re-pointing the accountingClass map: a class leg
     * then resolves to a DIFFERENT account, while a leg that names its account (a pinned reversal)
     * keeps the one it names, exactly as the real ledger behaves.
     */
    var mapGeneration = 1

    fun resolve(line: PostingLine): UUID = line.glAccountId ?: UUID.nameUUIDFromBytes(
        "${line.accountingClass}|${line.role}|${line.currency}|g$mapGeneration".toByteArray(),
    )

    override suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): PostedJournal {
        beforePost(spec.idempotencyKey)
        if (spec.dealId == failFor) error("ledger unavailable")
        if (spec.dealId == refuseFor) throw LedgerRefused("422 the ledger refused ${spec.idempotencyKey}")
        posted += spec
        if (spec.dealId == loseAnswerFor) {
            loseAnswerFor = null
            error("simulated: the ledger committed ${spec.idempotencyKey} and the answer was lost")
        }
        entryDates[spec.idempotencyKey] = entryDate
        onPost()
        return PostedJournal(
            UUID.nameUUIDFromBytes(spec.idempotencyKey.toByteArray()),
            spec.lines.map { ResolvedAccount(it.accountingClass, it.role, it.currency, resolve(it)) },
        )
    }

    override fun isDefinitiveRefusal(failure: Throwable) = failure is LedgerRefused
}

internal class LedgerRefused(message: String) : RuntimeException(message)

/** #13883: posting intents in memory; [InMemoryDeals.save] completes one as the real save does. */
internal class InMemoryIntents : PostingIntentPort {
    enum class Status { OPEN, COMPLETED, REFUSED, CLOSED_BY_OPERATOR }

    val rows = linkedMapOf<String, Pair<PostingIntent, Status>>()
    val closedBy = mutableMapOf<String, String>()
    val escalated = mutableSetOf<String>()

    /** Append-only, as `deal_posting_intent_history`: (key, transition, actor, reason, value day). */
    val history = mutableListOf<List<Any?>>()

    fun status(key: String) = rows[key]?.second

    private fun stored(key: String) = rows.getValue(key).let { (i, st) ->
        i.copy(status = PostingIntentStatus.valueOf(st.name))
    }

    private fun record(key: String, transition: String, actor: String?, reason: String?) {
        history += listOf(key, transition, actor, reason, rows.getValue(key).first.valueDate)
    }

    /** Runs before every open (a test races a concurrent write in here). */
    var onOpen: suspend () -> Unit = {}

    override suspend fun open(intent: PostingIntent): PostingIntent {
        onOpen()
        val key = intent.idempotencyKey
        val existing = rows[key]
        // As the real store: a new or REFUSED key takes this attempt whole (fresh).
        if (existing == null || existing.second == Status.REFUSED) {
            rows[key] = intent.copy(fresh = false) to Status.OPEN
            record(key, if (existing == null) "OPENED" else "REOPENED", intent.actor.id, null)
            return stored(key).copy(fresh = true)
        }
        if (existing.second == Status.CLOSED_BY_OPERATOR) {
            // A journal may exist: reopened NOT fresh, keeping its first value day; the re-issuer's
            // decision (actor, time) and no longer escalated.
            rows[key] = existing.first.copy(actor = intent.actor, createdAt = intent.createdAt) to Status.OPEN
            escalated -= key
            record(key, "REOPENED_AFTER_OPERATOR_CLOSE", intent.actor.id, null)
        }
        return stored(key)
    }

    override suspend fun refuse(key: String, reason: String) {
        rows[key]?.takeIf { it.second == Status.OPEN }?.let {
            rows[key] = it.first.copy(lastError = reason) to Status.REFUSED
            record(key, "REFUSED", null, reason)
        }
    }

    override suspend fun failed(key: String, error: String) {
        rows[key]?.takeIf { it.second == Status.OPEN }?.let {
            rows[key] = it.first.copy(attempts = it.first.attempts + 1, lastError = error) to Status.OPEN
        }
    }

    override suspend fun escalate(key: String, reason: String) {
        rows[key]?.takeIf { it.second == Status.OPEN }?.let {
            rows[key] = it.first.copy(lastError = reason) to Status.OPEN
            escalated += key
        }
    }

    override suspend fun claimOpen(cutoff: Instant, lease: Duration, limit: Int) = rows.values.filter {
        it.second == Status.OPEN && it.first.idempotencyKey !in escalated && it.first.createdAt.isBefore(cutoff)
    }.sortedBy { it.first.createdAt }.take(limit).map { it.first }

    override suspend fun openFor(dealId: UUID) =
        rows.values.filter { it.second == Status.OPEN && it.first.dealId == dealId }.map { it.first }

    override suspend fun blocksAccrual(dealId: UUID) = rows.values.any { (i, st) ->
        i.dealId == dealId &&
            (
                st == Status.OPEN ||
                    (
                        st == Status.CLOSED_BY_OPERATOR &&
                            i.action != com.openbank.treasury.application.port.out.PostingIntentAction.SETTLE
                        )
                )
    }

    override suspend fun summary() = rows.values.filter { it.second == Status.OPEN }
        .let { open -> OpenIntentSummary(open.size, open.minOfOrNull { it.first.createdAt }) }

    override suspend fun closeByOperator(
        key: String,
        actor: Actor,
        reason: String,
        createdBefore: Instant,
    ): PostingIntentCloseOutcome {
        val open = rows[key]?.takeIf { it.second == Status.OPEN } ?: return PostingIntentCloseOutcome.NOT_OPEN
        val row =
            open.takeIf { it.first.createdAt.isBefore(createdBefore) } ?: return PostingIntentCloseOutcome.IN_FLIGHT
        rows[key] = row.first to Status.CLOSED_BY_OPERATOR
        closedBy[key] = actor.id
        record(key, "CLOSED_BY_OPERATOR", actor.id, reason)
        return PostingIntentCloseOutcome.CLOSED
    }

    fun complete(key: String) {
        val row = rows[key] ?: return
        when (row.second) {
            Status.OPEN -> {
                rows[key] = row.first to Status.COMPLETED
                record(key, "COMPLETED", row.first.actor.id, null)
            }
            // As the real save: a closed record is never rewritten; the late journal is recorded beside it.
            Status.CLOSED_BY_OPERATOR, Status.REFUSED ->
                record(key, "JOURNAL_APPLIED_AFTER_CLOSE", row.first.actor.id, row.second.name)
            Status.COMPLETED -> Unit
        }
    }
}

internal class InMemoryDeals(private val intents: InMemoryIntents? = null) : DealRepository {
    val rows = linkedMapOf<UUID, Deal>()
    val journals = mutableListOf<LedgerJournalRef>()
    val events = mutableListOf<DealEvent>()

    val commands = mutableMapOf<String, CommandKey>()

    /** When true, the next save throws (nothing of it is kept), as a rolled-back transaction. */
    var failNextSave = false

    val schedules = mutableListOf<com.openbank.treasury.application.port.out.ScheduleWrite>()

    override suspend fun save(
        deal: Deal,
        journal: LedgerJournalRef?,
        event: DealEvent?,
        command: CommandKey?,
        schedule: com.openbank.treasury.application.port.out.ScheduleWrite?,
        extraEvents: List<DealEvent>,
    ): Deal {
        if (failNextSave) {
            failNextSave = false
            error("simulated: the state-change transaction rolled back")
        }
        rows[deal.id] = deal
        journal?.let { intents?.complete(it.idempotencyKey) }
        journal?.let { recordJournal(it) } // the real save skips a journal already recorded by key
        event?.let { events += it }
        events += extraEvents
        schedule?.let { schedules += it }
        command?.let { commands[it.key] = it }
        return deal
    }

    override suspend fun findCommand(key: String) = commands[key]

    override suspend fun findById(dealId: UUID) = rows[dealId]
    override suspend fun list(state: DealState?) = rows.values.filter { state == null || it.state == state }
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
    override suspend fun recordJournal(journal: LedgerJournalRef): Boolean {
        if (journals.any { it.idempotencyKey == journal.idempotencyKey }) return false
        journals += journal
        return true
    }
}
