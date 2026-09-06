// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.`in`.AttestYearCloseCommand
import com.openbank.ledger.application.port.`in`.CreateYearCloseDraftCommand
import com.openbank.ledger.application.port.`in`.GetFiscalYearTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.GetYearCloseQuery
import com.openbank.ledger.application.port.`in`.VerifyYearCloseQuery
import com.openbank.ledger.application.port.`in`.YearCloseUseCase
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.application.port.out.YearCloseRepository
import com.openbank.ledger.domain.event.YearCloseAttestedEvent
import com.openbank.ledger.domain.model.FiscalYearTrialBalance
import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.ledger.domain.model.YearCloseStatus
import com.openbank.ledger.domain.model.YearCloseVerification
import com.openbank.ledger.domain.model.requireValid
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.Year

/**
 * Entity-level statutory year-close, increment 1 (ADR-0078 D5 / issue #471): fiscal-year GL
 * trial balance + a hash-anchored, attestable [YearCloseRecord].
 *
 * Fail-closed invariants:
 *  - a trial balance that does not balance (sum debits != sum credits) can never become a DRAFT
 *    or be attested — that is a corrupt-journal signal, not a closable state;
 *  - attestation re-verifies the DRAFT's content hash against a FRESH computation; any drift
 *    (late postings into the closed year) is a conflict, never silently re-anchored;
 *  - only a fiscal year that has fully ended (bank time, Europe/Prague) can be attested;
 *  - an ATTESTED year is immutable — no draft refresh, no re-attest.
 */
@ApplicationScoped
class YearCloseService(
    private val journalRepository: JournalRepository,
    private val yearCloseRepository: YearCloseRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val accountingClock: AccountingClock,
) : YearCloseUseCase {
    // Single constructor = the CDI entry point. ADR-0207 D1: the second, @Inject constructor that
    // used to sit here built `Clock.system(Europe/Prague)` while this service's ClockProducer
    // produced `Clock.systemUTC()` — the two disagreed about the date for two hours a day, half
    // the year, and a year-close cutoff is exactly the decision that must not depend on which
    // object you ask. The wall clock is now the injected UTC bean (timestamps); the accounting
    // date comes from AccountingClock, the single authority for it.

    override suspend fun getTrialBalance(query: GetFiscalYearTrialBalanceQuery): FiscalYearTrialBalance =
        computeTrialBalance(query.fiscalYear)

    override suspend fun createDraft(command: CreateYearCloseDraftCommand): YearCloseRecord {
        val existing = yearCloseRepository.findByFiscalYear(command.fiscalYear)
        if (existing != null && existing.status == YearCloseStatus.ATTESTED) {
            throw YearCloseConflictException(
                "Year close ${command.fiscalYear} is already ATTESTED and immutable",
            )
        }
        val trialBalance = computeTrialBalance(command.fiscalYear)
        requireBalanced(trialBalance)
        // Idempotent per year while DRAFT: refresh keeps the record id stable, only the
        // snapshot (totals, hash, computedAt) moves to the current journal state.
        val draft = YearCloseRecord.draftOf(
            trialBalance = trialBalance,
            computedAt = Instant.now(clock),
            id = existing?.id ?: java.util.UUID.randomUUID(),
            // The maker: whoever produced THIS snapshot. A refresh updates it to the current actor
            // so the attestor (checker) always reviews against the author of the reviewed snapshot.
            draftedBy = command.draftedBy,
        )
        return yearCloseRepository.saveDraft(draft)
    }

    override suspend fun attest(command: AttestYearCloseCommand): YearCloseRecord {
        val record = yearCloseRepository.findByFiscalYear(command.fiscalYear)
            ?: throw YearCloseNotFoundException("No year close for fiscal year ${command.fiscalYear}")
        if (record.status != YearCloseStatus.DRAFT) {
            throw YearCloseConflictException(
                "Year close ${command.fiscalYear} is not DRAFT (status=${record.status})",
            )
        }
        if (command.fiscalYear >= accountingClock.today().year) {
            throw YearCloseConflictException(
                "Fiscal year ${command.fiscalYear} has not ended yet — cannot attest an open year",
            )
        }

        // Re-verify the anchor against a FRESH computation (fail-closed): if anything was posted
        // into the closed year since the draft, the hash drifts and attestation must stop.
        val fresh = computeTrialBalance(command.fiscalYear)
        requireBalanced(fresh)
        val freshHash = fresh.contentHash()
        if (freshHash != record.contentHash) {
            throw YearCloseConflictException(
                "Trial balance for ${command.fiscalYear} changed since the draft was computed " +
                    "(expected ${record.contentHash}, got $freshHash) — refresh the draft and re-review",
            )
        }

        // Four-eyes (maker != checker, #869): the attestor must differ from the draft author.
        // Fail-closed on a null author — a draft created before four-eyes tracking has no recorded
        // maker, so we CANNOT prove separation of duties and must reject rather than silently pass.
        if (record.draftedBy == null) {
            throw YearCloseConflictException(
                "Year close ${command.fiscalYear} draft predates four-eyes tracking " +
                    "(no recorded author) — refresh it so a maker is recorded, then attest as a different user",
            )
        }
        if (record.draftedBy == command.attestedBy) {
            throw YearCloseConflictException(
                "Four-eyes: the attestor must differ from the draft author ${record.draftedBy} " +
                    "for year ${command.fiscalYear}",
            )
        }

        val attested = record.attest(attestedBy = command.attestedBy, attestedAt = Instant.now(clock))
        val outbox = OutboxMessage(
            aggregateId = attested.id,
            eventType = YEAR_CLOSE_ATTESTED,
            payload = objectMapper.writeValueAsString(
                YearCloseAttestedEvent(
                    aggregateId = attested.id,
                    version = 1L,
                    occurredAt = clock.instant(),
                    fiscalYear = attested.fiscalYear,
                    contentHash = attested.contentHash,
                    totalDebits = attested.totalDebits,
                    totalCredits = attested.totalCredits,
                    accountCount = attested.accountCount,
                    attestedBy = attested.attestedBy!!,
                    attestedAt = attested.attestedAt!!,
                ),
            ),
        )
        // Status flip + outbox row commit atomically (transactional outbox, ADR-0003/0050).
        return yearCloseRepository.saveAttested(attested, outbox)
    }

    override suspend fun getYearClose(query: GetYearCloseQuery): YearCloseRecord =
        yearCloseRepository.findByFiscalYear(query.fiscalYear)
            ?: throw YearCloseNotFoundException("No year close for fiscal year ${query.fiscalYear}")

    /**
     * Re-verify a year-close record against a fresh trial-balance computation, read-only (#869).
     * Unlike [attest] this never throws on drift or imbalance and never flips state — it reports
     * the verdict, so an auditor can continuously prove a sealed (ATTESTED) period is unchanged or
     * detect a stale DRAFT. Only an absent record is an error (404).
     */
    override suspend fun verify(query: VerifyYearCloseQuery): YearCloseVerification {
        val record = yearCloseRepository.findByFiscalYear(query.fiscalYear)
            ?: throw YearCloseNotFoundException("No year close for fiscal year ${query.fiscalYear}")
        val fresh = computeTrialBalance(query.fiscalYear)
        val freshHash = fresh.contentHash()
        return YearCloseVerification(
            fiscalYear = query.fiscalYear,
            status = record.status,
            recordedHash = record.contentHash,
            recomputedHash = freshHash,
            matches = freshHash == record.contentHash,
            balanced = fresh.isBalanced,
            recomputedAt = Instant.now(clock),
        )
    }

    // Real-only by the port's default (ADR-0252 / LedgerScope). The fiscal-year close hash is an
    // attestation over the statutory population; canary activity is not part of it.
    private suspend fun computeTrialBalance(fiscalYear: Int): FiscalYearTrialBalance {
        requireValid(fiscalYear in YearCloseRecord.MIN_FISCAL_YEAR..YearCloseRecord.MAX_FISCAL_YEAR) {
            "fiscalYear out of range: $fiscalYear"
        }
        val year = Year.of(fiscalYear)
        val from = year.atDay(1)
        val to = year.atDay(year.length())
        return FiscalYearTrialBalance(
            fiscalYear = fiscalYear,
            lines = journalRepository.trialBalanceForPeriod(from, to),
        )
    }

    private fun requireBalanced(trialBalance: FiscalYearTrialBalance) {
        if (!trialBalance.isBalanced) {
            throw YearCloseConflictException(
                "GL does not balance for ${trialBalance.fiscalYear}: " +
                    "debits=${trialBalance.totalDebit} credits=${trialBalance.totalCredit} — " +
                    "double-entry invariant violated, close blocked",
            )
        }
    }

    companion object {
        private const val YEAR_CLOSE_ATTESTED = "YearCloseAttested"
        // The BANK_TIME constant that used to live here is gone — the accounting time zone is
        // declared once, in com.openbank.libs.domain.calendar.AccountingClock (ADR-0207 D1).
    }
}

class YearCloseNotFoundException(message: String) : RuntimeException(message)
class YearCloseConflictException(message: String) : RuntimeException(message)
