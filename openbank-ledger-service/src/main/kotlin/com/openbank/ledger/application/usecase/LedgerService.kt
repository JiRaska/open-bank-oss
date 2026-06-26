// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import com.openbank.libs.domain.money.Money
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

@ApplicationScoped
class LedgerService(
    private val journalRepository: JournalRepository,
    private val glAccountRepository: GlAccountRepository,
    private val objectMapper: ObjectMapper,
    private val metrics: DomainMetrics,
    private val yearCloseRepository: YearCloseRepository,
    private val clock: Clock,
) : LedgerUseCase {

    // CDI entry point: injects the production system clock in the bank time zone. Tests use the
    // primary constructor with a fixed Clock for deterministic timestamps (ADR-0100 Layer 1).
    @Inject
    constructor(
        journalRepository: JournalRepository,
        glAccountRepository: GlAccountRepository,
        objectMapper: ObjectMapper,
        metrics: DomainMetrics,
        yearCloseRepository: YearCloseRepository,
    ) : this(
        journalRepository,
        glAccountRepository,
        objectMapper,
        metrics,
        yearCloseRepository,
        Clock.system(BANK_TIME),
    )

    override suspend fun postJournal(command: PostJournalCommand): JournalEntry {
        // Idempotent replay: a repeated key returns the original entry, never double-posts.
        // Checked BEFORE the period lock so replaying an entry that was legitimately booked while
        // the year was still open stays idempotent even after the year is later attested.
        journalRepository.findByIdempotencyKey(command.idempotencyKey)?.let { return it }

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
        check(glAccounts.keys.containsAll(entry.lines.map { it.glAccountId }.toSet())) {
            "Unvalidated GL account referenced in journal entry"
        }

        val outbox = OutboxMessage(
            aggregateId = entry.id,
            eventType = JOURNAL_POSTED,
            payload = objectMapper.writeValueAsString(
                JournalPostedEvent(
                    aggregateId = entry.id,
                    version = entry.version,
                    entryNumber = entry.entryNumber!!,
                    transactionId = entry.transactionId,
                    entryDate = entry.entryDate,
                    lineCount = entry.lines.size,
                ),
            ),
        )

        val saved = journalRepository.save(entry, command.idempotencyKey, listOf(outbox) + bookedChangedMessages(entry))
        recordPostings(entry, POSTING, metrics)
        return saved
    }

    override suspend fun reverseJournal(command: ReverseJournalCommand): JournalEntry {
        val original = journalRepository.findById(command.journalId)
            ?: throw JournalNotFoundException("Journal not found: ${command.journalId}")

        // Period lock (#869): a reversal inherits the original entry's date (reverse() preserves
        // entryDate), so reversing a prior-year entry would post into that year. If that year is
        // ATTESTED the period is locked — block it; corrections to a closed year must be booked as
        // an adjustment in the current open period, not into the sealed one.
        requireOpenPeriod(original.entryDate.year)

        val reversalId = UUID.randomUUID()
        // A reversal is itself a journal entry and needs its own unique entry number
        // (uq_journal_entry_number); reverse() leaves it null, so assign one here.
        // reverse() inherits the original's timestamp as a placeholder; stamp the real reversal
        // time here from the injected clock (ADR-0100 Layer 1 — application owns the clock).
        val reversal = original.reverse(reversalId, command.reversedBy)
            .copy(entryNumber = journalRepository.nextEntryNumber(), createdAt = clock.instant())

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
    private fun bookedChangedMessages(entry: JournalEntry): List<OutboxMessage> = entry.bookedDeltas().map { d ->
        OutboxMessage(
            aggregateId = d.accountId,
            eventType = ACCOUNT_BOOKED_CHANGED,
            payload = objectMapper.writeValueAsString(
                AccountBookedChangedEvent(
                    aggregateId = d.accountId,
                    version = entry.version,
                    currency = d.currency,
                    delta = d.delta,
                    journalEntryId = entry.id,
                    transactionId = entry.transactionId,
                    entryDate = entry.entryDate,
                ),
            ),
        )
    }

    companion object {
        // Bank time zone for the default (CDI) system clock — matches YearCloseService.
        private val BANK_TIME = ZoneId.of("Europe/Prague")

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

class JournalNotFoundException(message: String) : RuntimeException(message)
class GlAccountValidationException(message: String) : RuntimeException(message)

/** A posting or reversal targeted an ATTESTED (locked) fiscal period. Mapped to 409 (#869). */
class ClosedFiscalPeriodException(message: String) : RuntimeException(message)
