// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.`in`.ClosedPeriodUseCase
import com.openbank.ledger.application.port.`in`.CreateClosedPeriodDraftCommand
import com.openbank.ledger.application.port.`in`.FreezeClosedPeriodCommand
import com.openbank.ledger.application.port.`in`.GetClosedPeriodQuery
import com.openbank.ledger.application.port.`in`.GetPeriodTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.ListClosedPeriodsQuery
import com.openbank.ledger.application.port.`in`.VerifyClosedPeriodQuery
import com.openbank.ledger.application.port.out.ClosedPeriodRepository
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.domain.event.PeriodFrozenEvent
import com.openbank.ledger.domain.model.AccountingPeriod
import com.openbank.ledger.domain.model.ClosedPeriodEvidenceState
import com.openbank.ledger.domain.model.ClosedPeriodRecord
import com.openbank.ledger.domain.model.ClosedPeriodStatus
import com.openbank.ledger.domain.model.ClosedPeriodVerification
import com.openbank.ledger.domain.model.PeriodTrialBalance
import com.openbank.ledger.domain.model.PeriodType
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.ledger.domain.model.requireValid
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * The statutory period close (ADR-0096 D1), with the ledger as sole golden source (ADR-0039).
 *
 * Mirrors the fiscal-year close (ADR-0078 D5) one granularity down: DRAFT is recomputable, FROZEN
 * is immutable and hash-anchored, and the freeze re-verifies the hash fail-closed against a fresh
 * computation so a period cannot be sealed over numbers that moved since the draft was reviewed.
 *
 * Deliberately NOT merged with `YearCloseService`: fiscal-year attestation carries its own
 * lifecycle, its own table and its own already-attested hashes, and folding them together would
 * either change those hashes or leave two code paths pretending to be one.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // One cohesive statutory-close use case; splitting would blur its atomic invariants.
class ClosedPeriodService(
    private val journalRepository: JournalRepository,
    private val closedPeriodRepository: ClosedPeriodRepository,
    private val objectMapper: ObjectMapper,
    private val accountingClock: AccountingClock,
    private val clock: Clock,
) : ClosedPeriodUseCase {

    override suspend fun getTrialBalance(query: GetPeriodTrialBalanceQuery): PeriodTrialBalance {
        val record = closedPeriodRepository.findByPeriod(query.period)
        // A FROZEN close is evidence, not a cache. Returning a fresh aggregate here would make a
        // regulatory reader observe data other than the hash-attested artefact.
        return if (record?.status == ClosedPeriodStatus.FROZEN &&
            record.evidenceState == ClosedPeriodEvidenceState.LINES_V1
        ) {
            PeriodTrialBalance(record.period, closedPeriodRepository.findFrozenLines(record.id)).also { evidence ->
                if (evidence.contentHash() != record.contentHash) {
                    // A legacy FROZEN close without V23 rows must never silently fall back to a
                    // mutable journal query. An empty ledger remains valid: its canonical hash
                    // still matches. Anything else is an operational evidence incident.
                    throw ClosedPeriodConflictException(
                        "Frozen evidence for ${record.period.label} is unavailable or does not match its attestation",
                    )
                }
            }
        } else {
            computeTrialBalance(query.period)
        }
    }

    override suspend fun getFrozenTrialBalance(query: GetPeriodTrialBalanceQuery): PeriodTrialBalance {
        val record = closedPeriodRepository.findByPeriod(query.period)
            ?: throw ClosedPeriodConflictException(
                "Period ${query.period.label} has no frozen line evidence; regulatory reporting is fail-closed",
            )
        return frozenEvidence(record)
    }

    /**
     * The live closing balance at the END of a month: cumulative booked activity up to and
     * including `period.to`. The period trial balance above is that month's MOVEMENTS only, which
     * is the right input for a close and the wrong one for a balance sheet (#12499).
     */
    override suspend fun getClosingBalance(query: GetPeriodTrialBalanceQuery): PeriodTrialBalance {
        requireMonth(query.period)
        return PeriodTrialBalance(query.period, journalRepository.trialBalance(query.period.to))
    }

    /**
     * The closing balance at the end of a FROZEN month, built only from attested evidence: the sum
     * of every FROZEN `LINES_V1` month close up to and including the target (#12499).
     *
     * A month's frozen lines are its movements, so a stock figure (FINREP F 01.01, COREP own funds)
     * is the cumulative sum of them. Two fail-closed checks make that sum trustworthy:
     * - the target month itself must be FROZEN `LINES_V1` with a matching hash, as for the single
     *   period read, and so must every summed month;
     * - the sum must equal the journal's cumulative balance at `period.to`, per account and
     *   currency. Frozen months cannot accept postings, so the only way they can disagree is a
     *   month with postings that was never frozen (or is legacy `HASH_ONLY`) — a gap in the
     *   evidence chain, which this refuses rather than reporting a balance short by that month.
     *
     * The journal is read as a guard only; every number returned comes from frozen evidence.
     */
    override suspend fun getFrozenClosingBalance(query: GetPeriodTrialBalanceQuery): PeriodTrialBalance {
        requireMonth(query.period)
        val target = getFrozenTrialBalance(query)
        val earlierMonths = closedPeriodRepository.findRange(EVIDENCE_EPOCH, query.period.from.minusDays(1))
            .filter {
                it.period.type == PeriodType.MONTH &&
                    it.status == ClosedPeriodStatus.FROZEN &&
                    it.evidenceState == ClosedPeriodEvidenceState.LINES_V1
            }
        val evidence = earlierMonths.map { frozenEvidence(it) } + target
        val closing = PeriodTrialBalance(query.period, cumulate(evidence.flatMap { it.lines }))

        val journal = cumulate(journalRepository.trialBalance(query.period.to))
        if (closing.lines.keyed() != journal.keyed()) {
            throw ClosedPeriodConflictException(
                "Frozen evidence through ${query.period.label} does not add up to the journal balance at " +
                    "${query.period.to}: a month with postings is not FROZEN LINES_V1, so no attested " +
                    "closing balance exists; regulatory reporting is fail-closed",
            )
        }
        return closing
    }

    private suspend fun frozenEvidence(record: ClosedPeriodRecord): PeriodTrialBalance {
        if (record.status != ClosedPeriodStatus.FROZEN || record.evidenceState != ClosedPeriodEvidenceState.LINES_V1) {
            throw ClosedPeriodConflictException(
                "Period ${record.period.label} evidence state is ${record.evidenceState}; regulatory reporting requires FROZEN LINES_V1",
            )
        }
        return PeriodTrialBalance(record.period, closedPeriodRepository.findFrozenLines(record.id)).also { evidence ->
            if (evidence.contentHash() != record.contentHash) {
                throw ClosedPeriodConflictException(
                    "Frozen evidence for ${record.period.label} does not match its attestation",
                )
            }
        }
    }

    override suspend fun createDraft(command: CreateClosedPeriodDraftCommand): ClosedPeriodRecord {
        requirePeriodHasEnded(command.period)

        val existing = closedPeriodRepository.findByPeriod(command.period)
        if (existing != null && existing.status == ClosedPeriodStatus.FROZEN) {
            throw ClosedPeriodConflictException(
                "Period ${command.period.label} is already FROZEN and immutable",
            )
        }

        val trialBalance = computeTrialBalance(command.period)
        requireBalanced(trialBalance)

        // Idempotent while DRAFT: a refresh keeps the record id stable and moves only the snapshot,
        // so the checker always reviews a draft with a known author.
        return closedPeriodRepository.saveDraft(
            ClosedPeriodRecord.draftOf(
                trialBalance = trialBalance,
                computedAt = Instant.now(clock),
                id = existing?.id ?: Ids.newId(),
                draftedBy = command.draftedBy,
            ),
        )
    }

    override suspend fun freeze(command: FreezeClosedPeriodCommand): ClosedPeriodRecord {
        val record = closedPeriodRepository.findByPeriod(command.period)
            ?: throw ClosedPeriodNotFoundException("No close for period ${command.period.label}")

        // Fail closed on drift: the numbers must still be what the draft anchored. Freezing over a
        // changed trial balance would seal a hash describing something nobody reviewed.
        val fresh = computeTrialBalance(command.period)
        requireBalanced(fresh)
        val freshHash = fresh.contentHash()
        if (freshHash != record.contentHash) {
            throw ClosedPeriodConflictException(
                "Trial balance for ${command.period.label} changed since the draft was computed " +
                    "(expected ${record.contentHash}, got $freshHash) — refresh the draft and re-review",
            )
        }

        // Four-eyes (maker != checker) is enforced in the aggregate, which also refuses a draft
        // with no recorded author — without a maker there is nothing to separate the checker from.
        val frozen = record.freeze(command.frozenBy, Instant.now(clock))
        // Persist these exact re-verified lines in the same transaction as the status flip and
        // outbox row. A second repository read would leave a TOCTOU gap between hash verification
        // and what becomes statutory evidence.
        return closedPeriodRepository.saveFrozen(frozen, fresh, frozenMessage(frozen))
    }

    override suspend fun get(query: GetClosedPeriodQuery): ClosedPeriodRecord =
        closedPeriodRepository.findByPeriod(query.period)
            ?: throw ClosedPeriodNotFoundException("No close for period ${query.period.label}")

    override suspend fun verify(query: VerifyClosedPeriodQuery): ClosedPeriodVerification {
        val record = closedPeriodRepository.findByPeriod(query.period)
            ?: throw ClosedPeriodNotFoundException("No close for period ${query.period.label}")
        val fresh = computeTrialBalance(query.period)
        val freshHash = fresh.contentHash()
        return ClosedPeriodVerification(
            period = record.period,
            status = record.status,
            recordedHash = record.contentHash,
            recomputedHash = freshHash,
            matches = freshHash == record.contentHash,
            balanced = fresh.isBalanced,
            recomputedAt = Instant.now(clock),
        )
    }

    override suspend fun list(query: ListClosedPeriodsQuery): List<ClosedPeriodRecord> {
        requireValid(!query.from.isAfter(query.to)) { "from (${query.from}) must not be after to (${query.to})" }
        return closedPeriodRepository.findRange(query.from, query.to)
    }

    // Real-only by the port's default (ADR-0252 / LedgerScope): this aggregate is frozen into
    // LINES_V1 evidence and read by finrep-service for FINREP/COREP, so a canary posting inside it
    // would be a misstatement in a regulatory return. The period endpoints deliberately expose no
    // scope selector — a regulatory reader must not be able to ask for the synthetic population.
    private suspend fun computeTrialBalance(period: AccountingPeriod) =
        PeriodTrialBalance(period, journalRepository.trialBalanceForPeriod(period.from, period.to))

    /**
     * A period that has not ended cannot be closed — its own days are still accepting postings, so
     * any snapshot is a partial one wearing the label of a statutory close. Uses the ADR-0207
     * accounting-day authority rather than a wall clock, which is the whole point of that type.
     */
    private fun requirePeriodHasEnded(period: AccountingPeriod) {
        val today = accountingClock.today()
        if (!period.to.isBefore(today)) {
            throw ClosedPeriodConflictException(
                "Period ${period.label} has not ended (accounting day is $today) — cannot close an open period",
            )
        }
    }

    private fun requireMonth(period: AccountingPeriod) {
        requireValid(period.type == PeriodType.MONTH) {
            "A closing balance is defined per MONTH close; ${period.type} is not supported"
        }
    }

    /** Sum lines per (GL account, currency): movements of several months into one balance. */
    private fun cumulate(lines: List<TrialBalanceLine>): List<TrialBalanceLine> =
        lines.groupBy { it.glAccountId to it.currency }.values.map { group ->
            group.first().copy(
                totalDebit = group.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalDebit) },
                totalCredit = group.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalCredit) },
            )
        }.sortedWith(compareBy({ it.code }, { it.currency }))

    /** Scale-insensitive comparison key: 100 and 100.00 are the same amount. */
    private fun List<TrialBalanceLine>.keyed(): Map<Pair<String, String>, Pair<BigDecimal, BigDecimal>> =
        filter { it.totalDebit.signum() != 0 || it.totalCredit.signum() != 0 }
            .associate {
                (it.code to it.currency) to
                    (it.totalDebit.stripTrailingZeros() to it.totalCredit.stripTrailingZeros())
            }

    private fun requireBalanced(trialBalance: PeriodTrialBalance) {
        if (!trialBalance.isBalanced) {
            throw ClosedPeriodConflictException(
                "Trial balance for ${trialBalance.period.label} does not balance: " +
                    "debits ${trialBalance.totalDebit} != credits ${trialBalance.totalCredit}",
            )
        }
    }

    private fun frozenMessage(record: ClosedPeriodRecord) = OutboxMessage(
        aggregateId = record.id,
        eventType = PERIOD_FROZEN,
        payload = objectMapper.writeValueAsString(
            PeriodFrozenEvent(
                aggregateId = record.id,
                version = 0L,
                occurredAt = Instant.now(clock),
                periodLabel = record.period.label,
                periodType = record.period.type.name,
                periodFrom = record.period.from,
                periodTo = record.period.to,
                contentHash = record.contentHash,
                totalDebits = record.totalDebits,
                totalCredits = record.totalCredits,
                accountCount = record.accountCount,
                frozenBy = record.frozenBy!!,
                frozenAt = record.frozenAt!!,
            ),
        ),
    )

    companion object {
        private const val PERIOD_FROZEN = "PeriodFrozen"

        /** Lower bound for "every month close up to X"; no evidence predates the ledger. */
        private val EVIDENCE_EPOCH: LocalDate = LocalDate.of(1970, 1, 1)
    }
}

/** No close record for the requested period. Mapped to 404. */
class ClosedPeriodNotFoundException(message: String) : RuntimeException(message)

/** Fail-closed close invariant: frozen immutability, hash drift, unbalanced GL, open period. 409. */
class ClosedPeriodConflictException(message: String) : RuntimeException(message)
