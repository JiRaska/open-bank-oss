// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.`in`.GetControlAccountTieOutQuery
import com.openbank.ledger.application.port.`in`.GetJournalQuery
import com.openbank.ledger.application.port.`in`.GetJournalsByTransactionQuery
import com.openbank.ledger.application.port.`in`.GetSubLedgerBalancesQuery
import com.openbank.ledger.application.port.`in`.GetTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.ListJournalsQuery
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.ReverseJournalCommand
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.application.port.out.YearCloseRepository
import com.openbank.ledger.domain.event.AccountBookedChangedEvent
import com.openbank.ledger.domain.event.JournalPostedEvent
import com.openbank.ledger.domain.event.JournalReversedEvent
import com.openbank.ledger.domain.model.ControlAccountTieOut
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.ledger.domain.model.SubLedgerBalance
import com.openbank.ledger.domain.model.TrialBalance
import com.openbank.ledger.domain.model.checkConflict
import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import com.openbank.libs.domain.money.Money
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.vertx.pgclient.PgException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.PersistenceException
import java.time.Clock
import java.util.UUID

@ApplicationScoped
class LedgerService(
    private val journalRepository: JournalRepository,
    private val glAccountRepository: GlAccountRepository,
    private val objectMapper: ObjectMapper,
    private val metrics: DomainMetrics,
    private val yearCloseRepository: YearCloseRepository,
    private val accountingDayLock: AccountingDayLock,
    private val periodFreezeLock: PeriodFreezeLock,
    private val clock: Clock,
) : LedgerUseCase {
    /** Field injection keeps the established money-path constructor shape stable for direct tests. */
    @Inject
    lateinit var tracer: Tracer
    // The single constructor is the CDI entry point; tests pass a fixed Clock for deterministic
    // timestamps (ADR-0100 Layer 1).
    //
    // ADR-0207 D1: there used to be a second, @Inject constructor here that built
    // `Clock.system(Europe/Prague)` while ClockProducer produced `Clock.systemUTC()` — two regimes
    // inside one service, disagreeing about the date for two hours a day, half the year, with
    // nothing able to detect it because both answers are individually plausible. The wall clock is
    // now the injected UTC bean (correct for timestamps); the accounting DATE comes from
    // AccountingClock via [accountingDayLock], the single authority for it.

    /**
     * A successful posting is a money-path execution boundary. The span deliberately excludes
     * journal, account, transaction, actor, amount, and idempotency data: it proves execution
     * without becoming a second financial record.
     */
    @Suppress("TooGenericExceptionCaught") // The span must record every failure before propagation.
    override suspend fun postJournal(command: PostJournalCommand): JournalEntry {
        val span = activeTracer().spanBuilder("ledger.journal.post")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan()
        return try {
            postJournalInternal(command).also { entry ->
                span.setAttribute("openbank.ledger.journal.status", entry.status.name)
            }
        } catch (failure: Exception) {
            span.recordException(failure)
            span.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            span.end()
        }
    }

    private fun activeTracer(): Tracer =
        if (::tracer.isInitialized) tracer else GlobalOpenTelemetry.getTracer("openbank-ledger-service")

    private suspend fun postJournalInternal(command: PostJournalCommand): JournalEntry {
        // Idempotent replay: a repeated key returns the original entry, never double-posts.
        // Checked BEFORE the period lock so replaying an entry that was legitimately booked while
        // the year was still open stays idempotent even after the year is later attested.
        journalRepository.findByIdempotencyKey(command.idempotencyKey)?.let { return it }

        // Day lock (ADR-0207 D3) runs BEFORE the year check because the day is the tighter
        // constraint: a day already CUTOFF/TIED_OUT/LOCKED refuses a posting even inside an open
        // fiscal year. Ships in shadow mode — it records what it *would* have refused without
        // refusing, so the volume of currently-legal backdated postings is measured before any
        // start failing (#1197: turning on a new money-path refusal blind killed five workloads
        // for four days).
        accountingDayLock.requireOpen(command.entryDate, AccountingDayLock.OPERATION_POSTING)

        // Period lock (ADR-0096 D1) sits between the two: tighter than the fiscal year, looser than
        // the day. A month can be FROZEN inside an unattested year, and a day can be LOCKED inside
        // an unfrozen month, so neither of its neighbours can express this state. Also shadow-first.
        periodFreezeLock.requireOpen(command.entryDate, AccountingDayLock.OPERATION_POSTING)

        // Period lock (#869): an ATTESTED fiscal year is closed; new activity into it would
        // silently invalidate the attested trial-balance hash. Reject before doing any work.
        requireOpenPeriod(command.entryDate.year)

        val glAccounts = loadAndValidateGlAccounts(command.lines)

        val entryNumber = journalRepository.nextEntryNumber()
        val journalId = UUID.randomUUID()

        val lines = command.lines.mapIndexed { index, req ->
            JournalLine(
                id = UUID.randomUUID(),
                journalId = journalId,
                glAccountId = req.glAccountId,
                side = req.side,
                amount = Money.of(req.amount, req.currencyCode),
                fxRate = req.fxRate,
                baseAmount = Money.of(req.baseAmount, req.baseCurrencyCode),
                sequence = index + 1,
                subAccountId = req.subAccountId,
            )
        }

        // JournalEntry's init enforces double-entry: >=2 lines and balanced debits == credits.
        val entry = JournalEntry(
            id = journalId,
            entryNumber = entryNumber,
            transactionId = command.transactionId,
            entryDate = command.entryDate,
            valueDate = command.valueDate,
            description = command.description,
            status = JournalStatus.PENDING,
            lines = lines,
            createdAt = clock.instant(),
            createdBy = command.postedBy,
            version = 0L,
        ).post()

        // Cross-check that we actually validated every account that the entry references.
        checkConflict(glAccounts.keys.containsAll(entry.lines.map { it.glAccountId }.toSet())) {
            "Unvalidated GL account referenced in journal entry"
        }

        val messages =
            listOf(journalPostedMessage(entry, command.synthetic)) +
                bookedChangedMessages(entry, command.synthetic) +
                command.additionalOutboxMessages(entry)
        val saved = try {
            journalRepository.save(entry, command.idempotencyKey, messages)
        } catch (e: PersistenceException) {
            return recoverConcurrentReplay(e, command.idempotencyKey)
        } catch (e: PgException) {
            return recoverConcurrentReplay(e, command.idempotencyKey)
        }
        recordPostings(entry, POSTING, metrics)
        recordPostingAmounts(entry, metrics)
        return saved
    }

    /**
     * The loser of a concurrent duplicate-submission race: both contenders passed the replay
     * check before either committed, and this transaction died on the ledger_idempotency
     * primary key. Recover to the same contract as the sequential path — return the winner's
     * entry (idempotent replay; no metrics, a replay is never a second posting). Anything that
     * is not the idempotency-key conflict propagates untouched.
     */
    private suspend fun recoverConcurrentReplay(e: RuntimeException, idempotencyKey: String): JournalEntry {
        val isIdempotencyKeyConflict = generateSequence<Throwable>(e) { it.cause.takeIf { c -> c !== it } }
            .any { it.message?.contains("ledger_idempotency", ignoreCase = true) == true }
        if (!isIdempotencyKeyConflict) throw e
        return journalRepository.findByIdempotencyKey(idempotencyKey) ?: throw e
    }

    private fun journalPostedMessage(entry: JournalEntry, synthetic: Boolean): OutboxMessage = OutboxMessage(
        aggregateId = entry.id,
        eventType = JOURNAL_POSTED,
        synthetic = synthetic,
        payload = objectMapper.writeValueAsString(
            JournalPostedEvent(
                aggregateId = entry.id,
                version = entry.version,
                entryNumber = entry.entryNumber!!,
                transactionId = entry.transactionId,
                entryDate = entry.entryDate,
                lineCount = entry.lines.size,
                occurredAt = clock.instant(),
            ),
        ),
    )

    override suspend fun reverseJournal(command: ReverseJournalCommand): JournalEntry {
        val original = journalRepository.findById(command.journalId)
            ?: throw JournalNotFoundException("Journal not found: ${command.journalId}")

        // Deterministic 409 for the sequential repeat; the concurrent window (both contenders
        // read POSTED here) is closed by the conditional status flip in saveReversal.
        if (original.status != JournalStatus.POSTED) {
            throw JournalReversalConflictException("Journal ${original.id} is not POSTED — already reversed")
        }

        // Period lock (#869): a reversal inherits the original entry's date (reverse() preserves
        // entryDate), so reversing a prior-year entry would post into that year. If that year is
        // ATTESTED the period is locked — block it; corrections to a closed year must be booked as
        // an adjustment in the current open period, not into the sealed one.
        requireOpenPeriod(original.entryDate.year)

        // Day lock (ADR-0207 D3), reversal variant: a reversal of an entry in a closed day is NOT
        // refused — it is exactly what that situation calls for. It is routed FORWARD instead, into
        // the current open day, so the closed day's tied-out figures stay true. Rewriting a
        // tied-out day in place is the operation being removed; correcting it forward is not.
        // In shadow mode the decision is recorded but the date is left alone, so no booking date
        // changes until the lock is deliberately enforced.
        val dayDecision = accountingDayLock.evaluate(original.entryDate, AccountingDayLock.OPERATION_REVERSAL)
        val frozenPeriod = periodFreezeLock.evaluate(original.entryDate, AccountingDayLock.OPERATION_REVERSAL)
        val sealed = (dayDecision.wouldRefuse && accountingDayLock.enforcing) ||
            (frozenPeriod != null && periodFreezeLock.enforcing)
        val correctionDate = if (sealed) accountingDayLock.forwardCorrectionDate() else original.entryDate

        val reversalId = UUID.randomUUID()
        // A reversal is itself a journal entry and needs its own unique entry number
        // (uq_journal_entry_number); reverse() leaves it null, so assign one here.
        // reverse() inherits the original's timestamp as a placeholder; stamp the real reversal
        // time here from the injected clock (ADR-0100 Layer 1 — application owns the clock).
        // entryDate is [correctionDate]: the original's own date normally, the current open day
        // when the original's day is sealed. reversalOf already links back to the original, so the
        // forward correction stays attributable to the entry it reverses.
        val reversal = original.reverse(reversalId, command.reversedBy)
            .copy(
                entryNumber = journalRepository.nextEntryNumber(),
                createdAt = clock.instant(),
                entryDate = correctionDate,
            )

        val outbox = OutboxMessage(
            aggregateId = reversal.id,
            eventType = JOURNAL_REVERSED,
            payload = objectMapper.writeValueAsString(
                JournalReversedEvent(
                    aggregateId = reversal.id,
                    version = reversal.version,
                    originalJournalId = original.id,
                    transactionId = original.transactionId,
                    reason = command.reason,
                    occurredAt = clock.instant(),
                ),
            ),
        )

        journalRepository.saveReversal(
            reversal,
            original.id,
            original.entryDate,
            listOf(outbox) + bookedChangedMessages(reversal),
        )
        recordPostings(reversal, REVERSAL, metrics)
        recordPostingAmounts(reversal, metrics)

        return original.copy(status = JournalStatus.REVERSED, version = original.version + 1)
    }

    override suspend fun getJournal(query: GetJournalQuery): JournalEntry = journalRepository.findById(query.journalId)
        ?: throw JournalNotFoundException("Journal not found: ${query.journalId}")

    override suspend fun getJournalsByTransaction(query: GetJournalsByTransactionQuery): List<JournalEntry> =
        journalRepository.findByTransactionId(query.transactionId)

    override suspend fun listJournals(query: ListJournalsQuery): CursorPage<JournalEntry> {
        val afterId = query.afterCursor?.let { UUID.fromString(CursorEncoder.decode(it)) }
        val entries = journalRepository.findByDateRange(query.fromDate, query.toDate, query.limit + 1, afterId)
        val hasNext = entries.size > query.limit
        val page = if (hasNext) entries.dropLast(1) else entries
        val nextCursor = if (hasNext) CursorEncoder.encode(page.last().id.toString()) else null
        return CursorPage(
            data = page,
            pagination = PageInfo(limit = query.limit, hasNextPage = hasNext, nextCursor = nextCursor),
        )
    }

    override suspend fun getTrialBalance(query: GetTrialBalanceQuery): TrialBalance =
        TrialBalance(asOf = query.asOf, lines = journalRepository.trialBalance(query.asOf))

    override suspend fun getSubLedgerBalances(query: GetSubLedgerBalancesQuery): List<SubLedgerBalance> =
        journalRepository.subLedgerBalances(query.asOf, query.subAccountId)

    override suspend fun getControlAccountTieOut(query: GetControlAccountTieOutQuery): List<ControlAccountTieOut> =
        journalRepository.controlAccountTieOut(query.controlAccountId, query.asOf)

    /** Fail closed if [fiscalYear] is an ATTESTED (locked) accounting period (#869). */
    private suspend fun requireOpenPeriod(fiscalYear: Int) {
        if (yearCloseRepository.isFiscalYearAttested(fiscalYear)) {
            throw ClosedFiscalPeriodException(
                "Fiscal year $fiscalYear is closed (ATTESTED) — no postings or reversals may be " +
                    "booked into a locked accounting period",
            )
        }
    }

    private suspend fun loadAndValidateGlAccounts(lines: List<JournalLineRequest>): Map<UUID, GlAccount> {
        val accounts = mutableMapOf<UUID, GlAccount>()
        lines.map { it.glAccountId }.toSet().forEach { id ->
            val account = glAccountRepository.findById(id)
                ?: throw GlAccountValidationException("GL account not found: $id")
            if (!account.isEnabled) {
                throw GlAccountValidationException("GL account ${account.code} is disabled")
            }
            if (!account.isLeaf) {
                throw GlAccountValidationException("GL account ${account.code} is not a posting (leaf) account")
            }
            accounts[id] = account
        }
        // Postings are kept in the account's base currency; the line's base currency must agree.
        lines.forEach { line ->
            val account = accounts.getValue(line.glAccountId)
            if (account.currency.code != line.baseCurrencyCode) {
                throw GlAccountValidationException(
                    "Line base currency ${line.baseCurrencyCode} does not match GL account ${account.code} currency ${account.currency.code}",
                )
            }
            // Sub-ledger dimension (ADR-0039 Phase B) is only meaningful on customer deposit-control
            // legs; carrying it on cash-clearing, FX-position or P&L legs would corrupt the per-account
            // tie-out, so reject it rather than silently storing an orphan dimension.
            if (line.subAccountId != null && !account.isDepositControl) {
                throw GlAccountValidationException(
                    "subAccountId is only allowed on deposit-control legs, not GL account ${account.code}",
                )
            }
        }
        return accounts
    }

    /**
     * One AccountBookedChanged outbox message per affected customer account (ADR-0039 Phase D).
     * Derived from the entry's deposit-control legs; empty for journals with no customer dimension
     * (pure GL movements). Computed from the SAME entry that is being persisted, so a reversal's
     * flipped sides naturally yield negated deltas, keyed by the reversal entry's id.
     */
    private fun bookedChangedMessages(entry: JournalEntry, synthetic: Boolean = false): List<OutboxMessage> =
        entry.bookedDeltas().map { d ->
            OutboxMessage(
                aggregateId = d.accountId,
                eventType = ACCOUNT_BOOKED_CHANGED,
                synthetic = synthetic,
                payload = objectMapper.writeValueAsString(
                    AccountBookedChangedEvent(
                        aggregateId = d.accountId,
                        version = entry.version,
                        currency = d.currency,
                        delta = d.delta,
                        journalEntryId = entry.id,
                        transactionId = entry.transactionId,
                        entryDate = entry.entryDate,
                        occurredAt = clock.instant(),
                    ),
                ),
            )
        }

    companion object {
        // The bank time zone constant that used to live here is gone: the accounting date is no
        // longer derived from a wall clock in this class. It is owned by
        // com.openbank.libs.domain.calendar.AccountingClock (ADR-0207 D1), whose BANK_ZONE is the
        // one place the zone is declared.

        private const val JOURNAL_POSTED = "JournalPosted"
        private const val JOURNAL_REVERSED = "JournalReversed"
        private const val ACCOUNT_BOOKED_CHANGED = "AccountBookedChanged"

        /** Posting types for the `openbank.ledger.postings` meter's `type` tag. */
        private const val POSTING = "posting"
        private const val REVERSAL = "reversal"
    }
}

/**
 * Count this posting on the `openbank.ledger.postings` meter (ADR-0077 / ADR-0079). A journal entry
 * balances *within* each base currency — each distinct base currency is a self-contained debit==credit
 * pair (see [JournalEntry.validateBalance]) — so we emit one count per distinct base currency. Only the
 * (low-cardinality) currency and posting type are tagged; never the entry id, amount, or account.
 * Idempotent replays return before this point, so a replay is never double-counted. Kept at file scope
 * (a pure function over the entry + meter) so [LedgerService] stays within detekt's per-class budget.
 */
private fun recordPostings(entry: JournalEntry, type: String, metrics: DomainMetrics) {
    entry.lines.map { it.baseAmount.currency.code }.toSet().forEach { currency ->
        metrics.ledgerPosting(currency, type)
    }
}

/**
 * Record monetary amount of each posting line on the `openbank.ledger.posting.amount` histogram
 * (ADR-0077 Tier C). Uses [JournalLine.baseAmount] (settlement in the account's base currency) to
 * keep amounts comparable across cross-currency journals. The absolute value is recorded; the
 * `debit_credit` tag carries the direction. Kept at file scope alongside [recordPostings] to stay
 * within detekt's per-class budget.
 */
private fun recordPostingAmounts(entry: JournalEntry, metrics: DomainMetrics) {
    entry.lines.forEach { line ->
        metrics.ledgerPostingAmount(
            currency = line.baseAmount.currency.code,
            debitCredit = line.side.name.lowercase(),
            amount = line.baseAmount.amount.abs(),
        )
    }
}

class JournalNotFoundException(message: String) : RuntimeException(message)
class GlAccountValidationException(message: String) : RuntimeException(message)

/**
 * A reversal targeted a journal that is not POSTED — repeated or concurrent reversal (#465).
 * Dedicated type (not IllegalStateException): libs-runtime and this service both register an
 * ExceptionMapper<IllegalStateException> (422 vs 409) and JAX-RS picks between same-type
 * providers non-deterministically (ADR-0049 D4), so the conflict status would flip-flop.
 */
class JournalReversalConflictException(message: String) : RuntimeException(message)

/** A posting or reversal targeted an ATTESTED (locked) fiscal period. Mapped to 409 (#869). */
class ClosedFiscalPeriodException(message: String) : RuntimeException(message)
