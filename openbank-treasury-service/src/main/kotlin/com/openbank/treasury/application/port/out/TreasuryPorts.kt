// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CurveSetView
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.ResolvedAccount
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Raised when a command names a deal that does not exist. Mapped to 404. */
class DealNotFoundException(dealId: UUID) : NoSuchElementException("deal $dealId not found")

/** A deal write was computed from a copy older than the stored row. Mapped to 409 DEAL_CHANGED. */
class DealChangedException(message: String) : IllegalStateException(message)

/** Raised when a deal names a counterparty not in the master. Mapped to 400 via IllegalArgumentException. */
class UnknownCounterpartyException(id: String) : IllegalArgumentException("unknown counterparty '$id'")

/** A journal the ledger accepted for one deal event. */
data class LedgerJournalRef(
    val dealId: UUID,
    val event: PostingEvent,
    val idempotencyKey: String,
    val journalId: UUID,
    val postedAt: Instant,
    /** The accounts the ledger resolved each leg to, so a reversal can offset exactly these. */
    val accounts: List<ResolvedAccount> = emptyList(),
    /**
     * The journal's value day: the economic accounting day it belongs to (an accrual's day, a
     * settlement's value date). ADR-0342: treasury's day-close count attributes the journal to it.
     * Null only for rows recorded before V26 (backfilled there from posted_at).
     */
    val valueDate: LocalDate? = null,
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
        /** ADR-0344 D4: the schedule generated with this change, stored in the same transaction. */
        schedule: ScheduleWrite? = null,
        /** Further outbox events of the same change (e.g. treasury.cashflow.generated). */
        extraEvents: List<DealEvent> = emptyList(),
    ): Deal

    /** The command previously recorded under [key], if any. */
    suspend fun findCommand(key: String): CommandKey?

    suspend fun findById(dealId: UUID): Deal?

    suspend fun list(state: DealState?): List<Deal>

    /**
     * Deals due for the simulated market: in one of [states] (see [Deal.settleableStates]) with
     * valueDate <= today; SETTLED with maturity <= today.
     */
    suspend fun dueForSettlement(today: LocalDate, states: Set<DealState>): List<Deal>

    suspend fun dueForMaturity(today: LocalDate): List<Deal>

    /** Outstanding placed principal with [counterpartyId] in [currency], excluding [excludeDealId]. */
    suspend fun exposure(counterpartyId: String, currency: String, excludeDealId: UUID?): BigDecimal

    /**
     * Every PENDING_APPROVAL deal carrying a senior limit override, across all counterparties and
     * currencies. Deliberately NOT grouped by currency here: which limit line an override sits on
     * is [Deal.limitCurrency], a domain rule the caller applies via [Deal.holdsActiveLimitOverride].
     */
    suspend fun pendingLimitOverrides(): List<Deal>

    suspend fun journals(dealId: UUID): List<LedgerJournalRef>

    /**
     * Record a journal the ledger accepted, idempotent on its key. True only when THIS call recorded
     * it: false for a key already recorded (a replayed post), so a caller counts each journal once.
     */
    suspend fun recordJournal(journal: LedgerJournalRef): Boolean
}

/**
 * ADR-0342 (#13882): counts a journal recorded for the first time whose value day has already ended
 * (a late post for that day). Never a refused post, never a replay of a recorded key.
 */
fun interface LateJournalMeter {
    fun late(event: com.openbank.treasury.domain.model.PostingEvent)

    companion object {
        val NONE = LateJournalMeter { }
    }
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
    /**
     * Posts [spec] valued on [valueDate]; the adapter books it in the ledger's current open day when
     * [valueDate] has already ended (ADR-0342). Returns the ledger's journal — the original one on an idempotent replay, with the accounts its
     * legs were ORIGINALLY resolved to.
     */
    suspend fun post(spec: JournalSpec, valueDate: LocalDate, description: String): PostedJournal

    /**
     * True only when [failure] (thrown by [post]) is an EXPLICIT ledger business refusal raised after
     * the ledger's idempotency replay check found no journal under the key (#13883). Anything else
     * (no answer, 5xx, 400/401/403/404, a 4xx without a known ledger error code) is unknown and
     * false: the ledger may hold the journal, and the intent stays open.
     */
    fun isDefinitiveRefusal(failure: Throwable): Boolean = false
}

/** The lifecycle command a posting intent belongs to (#13883). */
enum class PostingIntentAction { SETTLE, MATURE, REVERSE }

/** OPEN = decided and not applied; COMPLETED = applied with its state change; REFUSED = closed with no journal. */
enum class PostingIntentStatus { OPEN, COMPLETED, REFUSED }

/**
 * A ledger post treasury decided to make (#13883), written BEFORE the ledger is contacted and keyed
 * by the journal's idempotency key. It carries what the reconciler needs to re-run the command.
 */
data class PostingIntent(
    val idempotencyKey: String,
    val dealId: UUID,
    val event: PostingEvent,
    val action: PostingIntentAction,
    val valueDate: LocalDate,
    val actor: Actor,
    val reason: String? = null,
    val commandKey: String? = null,
    val createdAt: Instant,
    val attempts: Int = 0,
    val lastError: String? = null,
    val status: PostingIntentStatus = PostingIntentStatus.OPEN,
    /**
     * True when THIS call created the intent or reopened a REFUSED one, i.e. no earlier attempt of it
     * can have reached the ledger. Only a fresh intent may be closed REFUSED on a ledger refusal.
     */
    val fresh: Boolean = false,
)

/** OPEN intents at a glance, for the gauges: how many, and since when the oldest has waited. */
data class OpenIntentSummary(val count: Int, val oldestCreatedAt: Instant?)

/**
 * Posting intents (#13883). An intent is COMPLETED by [DealRepository.save] in the transaction that
 * stores the journal and the state change; while it is OPEN it holds its value day's close report.
 */
@Suppress("TooManyFunctions")
interface PostingIntentPort {
    /**
     * Records [intent] if its key has none yet, or reopens one closed REFUSED as a new attempt by
     * this caller (its value day included: a refused post left no journal); returns the stored intent
     * with [PostingIntent.fresh] set. An OPEN or COMPLETED intent is returned as stored and NOT fresh:
     * its value day is the first attempt's, so a retry on a later day asks for the same journal.
     */
    suspend fun open(intent: PostingIntent): PostingIntent

    /** The ledger's coded refusal of a fresh intent: closes it, and it no longer holds its day. */
    suspend fun refuse(key: String, reason: String)

    /** A reconciliation attempt failed or was escalated: counted with its cause, claim released, still OPEN. */
    suspend fun failed(key: String, error: String)

    /**
     * Claims OPEN intents created before [cutoff] that no live claim holds, oldest first, at most
     * [limit], leasing them for [lease] (`FOR UPDATE SKIP LOCKED`): two reconcilers never take one row.
     */
    suspend fun claimOpen(cutoff: Instant, lease: Duration, limit: Int): List<PostingIntent>

    /** OPEN intents of one deal. */
    suspend fun openFor(dealId: UUID): List<PostingIntent>

    suspend fun summary(): OpenIntentSummary

    /**
     * An operator closes an OPEN intent that cannot complete: REFUSED, with who and why recorded.
     * False when no OPEN intent has [key].
     */
    suspend fun closeByOperator(key: String, actor: Actor, reason: String): Boolean

    companion object {
        /** No intent store: the pre-#13883 behaviour. Unit tests of the lifecycle alone only. */
        val NONE = object : PostingIntentPort {
            override suspend fun open(intent: PostingIntent) = intent.copy(fresh = true)
            override suspend fun refuse(key: String, reason: String) = Unit
            override suspend fun failed(key: String, error: String) = Unit
            override suspend fun claimOpen(cutoff: Instant, lease: Duration, limit: Int) = emptyList<PostingIntent>()
            override suspend fun openFor(dealId: UUID) = emptyList<PostingIntent>()
            override suspend fun summary() = OpenIntentSummary(0, null)
            override suspend fun closeByOperator(key: String, actor: Actor, reason: String) = false
        }
    }
}

/**
 * fx-service's mid rate for `currency`/CZK on [asOf] (#10896), used only to FLAG an FX spot deal
 * rate outside tolerance. Null = no mid available. treasury has no fx-service client yet, so the
 * wired implementation is [NONE] and the check is off by default ([FxRateTolerance]).
 */
fun interface FxMidRatePort {
    suspend fun mid(currency: String, asOf: LocalDate): BigDecimal?

    companion object {
        val NONE = FxMidRatePort { _, _ -> null }
    }
}

/** Versioned config `openbank.treasury.fx-spot.rate-check.*`: off unless a mid source is wired. */
data class FxRateTolerance(val enabled: Boolean, val tolerancePercent: BigDecimal) {
    init {
        require(tolerancePercent.signum() >= 0) { "tolerance must not be negative" }
    }

    companion object {
        val DISABLED = FxRateTolerance(false, BigDecimal("2.0"))
    }
}

/**
 * The risk engine's latest curve set (ADR-0315 D9, ADR-0313 D4), read as treasury's own identity.
 * Null = the engine holds no curve set yet. Throws `QuoteUnavailableException` when the engine
 * cannot be read.
 */
fun interface CurveSetPort {
    suspend fun latest(): CurveSetView?
}

/** This service's outbox table; `persistInTransaction` chains inside the aggregate's transaction. */
interface TreasuryOutboxRepository : OutboxRepository {
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}

/** A journal the ledger holds for a posted [JournalSpec]: its id and where each leg landed. */
data class PostedJournal(val journalId: UUID, val accounts: List<ResolvedAccount> = emptyList())
