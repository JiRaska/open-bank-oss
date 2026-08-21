// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.statement.application.port.`in`.RestatePeriodUseCase
import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.BalancePort
import com.openbank.statement.application.port.out.BookedEntryPort
import com.openbank.statement.application.port.out.PocketAccountInfo
import com.openbank.statement.application.port.out.StatementOutboxMessage
import com.openbank.statement.application.port.out.StatementPeriodRepository
import com.openbank.statement.domain.model.CreditDebit
import com.openbank.statement.domain.model.PeriodCloseStatus
import com.openbank.statement.domain.model.StatementEntry
import com.openbank.statement.domain.model.StatementPeriod
import com.openbank.statement.domain.model.StatementSnapshot
import com.openbank.statement.domain.reconcile.ReconciliationPolicy
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Sum of credits minus debits over [entries] — the booked net movement (ADR-0035 §E). Shared by the
 * close and restatement paths so a correction can never compute movement differently from the close
 * it replaces. BigDecimal throughout: statement figures are exact, never floating point.
 */
internal fun netMovementOf(entries: List<StatementEntry>): BigDecimal = entries.fold(BigDecimal.ZERO) { acc, e ->
    when (e.creditDebit) {
        CreditDebit.CRDT -> acc.add(e.amount)
        CreditDebit.DBIT -> acc.subtract(e.amount)
    }
}

/**
 * Restatement of an already-closed statement period (ADR-0035 §D, issue #1302 item 5).
 *
 * Split out of [StatementService] rather than folded into it: the two paths share only their
 * reconciliation rule, and a correction is the one operation in this service that writes two rows.
 *
 * **What existed before this class: nothing.** `PeriodCloseStatus.SUPERSEDED` and
 * `supersedesSequence` were present in the model, the schema, the renderers and the published spec
 * and had **zero write sites fleet-wide** — so a period closed on wrong data was immutable, and
 * re-running the close returned the stale record unchanged through the idempotency branch. The only
 * remediation was hand-written SQL against a legal record.
 */
@ApplicationScoped
class StatementRestatementService(
    private val accountInfo: AccountInfoPort,
    private val bookedEntries: BookedEntryPort,
    private val balance: BalancePort,
    private val periods: StatementPeriodRepository,
) : RestatePeriodUseCase {

    /** Clock seam, as in [StatementService]: `closedAt` is stamped once and stored, so renders of the
     *  superseding page stay deterministic. Overridable in tests; CDI uses the default. */
    internal var clock: () -> Instant = Instant::now

    /** Opening balance = the prior close's closing, else balance-service's balance the day before.
     *  `priorClosing` excludes SUPERSEDED rows, so a restated earlier period cannot re-open a later
     *  one on a figure the bank has already retracted. */
    private fun openingBalance(accountId: UUID, currency: String, from: LocalDate): Uni<BigDecimal> =
        periods.priorClosing(accountId, currency, from).flatMap { prior ->
            if (prior != null) {
                Uni.createFrom().item(prior)
            } else {
                balance.closingBalance(accountId, currency, from.minusDays(1)).map { it.amount }
            }
        }

    override fun restatePocketPeriod(
        accountId: UUID,
        currency: String,
        from: LocalDate,
        to: LocalDate,
    ): Uni<StatementPeriod> = accountInfo.pocketAccount(accountId).flatMap { account ->
        periods.findByPeriod(accountId, currency, from, to).flatMap { standing ->
            if (standing == null) {
                Uni.createFrom().failure(NoClosedPeriodToRestateException(accountId, currency, from, to))
            } else {
                recomputeAndSupersede(account, currency, from, to, standing)
            }
        }
    }

    private fun recomputeAndSupersede(
        account: PocketAccountInfo,
        currency: String,
        from: LocalDate,
        to: LocalDate,
        standing: StatementPeriod,
    ): Uni<StatementPeriod> = bookedEntries.bookedEntries(account.accountId, currency, from, to).flatMap { entries ->
        openingBalance(account.accountId, currency, from).flatMap { opening ->
            balance.closingBalance(account.accountId, currency, to).flatMap { reported ->
                // Fail closed on exactly the same terms as a first close (ADR-0035 §E): a
                // restatement that cannot itself reconcile must not replace a record that does.
                when (val r = ReconciliationPolicy.reconcile(opening, netMovementOf(entries), reported.amount)) {
                    is ReconciliationPolicy.Result.Mismatch ->
                        Uni.createFrom().failure(
                            ReconciliationException(account.accountId, currency, from, to, r),
                        )
                    is ReconciliationPolicy.Result.Reconciled ->
                        if (unchanged(standing, opening, r.closingBalance, entries.size)) {
                            // Nothing to correct — do not burn a legal sequence on a no-op.
                            Uni.createFrom().item(standing)
                        } else {
                            supersede(account, currency, from, to, standing, entries, opening, r.closingBalance)
                        }
                }
            }
        }
    }

    /** BigDecimal identity by VALUE (`compareTo`), never `equals` — 1075.00 and 1075.0000 are the
     *  same amount and only `compareTo` says so. */
    private fun unchanged(
        standing: StatementPeriod,
        opening: BigDecimal,
        closing: BigDecimal,
        entryCount: Int,
    ): Boolean = standing.openingBalance.compareTo(opening) == 0 &&
        standing.closingBalance.compareTo(closing) == 0 &&
        standing.entryCount == entryCount

    private fun supersede(
        account: PocketAccountInfo,
        currency: String,
        from: LocalDate,
        to: LocalDate,
        standing: StatementPeriod,
        entries: List<StatementEntry>,
        opening: BigDecimal,
        closing: BigDecimal,
    ): Uni<StatementPeriod> = periods.nextLegalSequence(account.accountId, currency).flatMap { seq ->
        val replacement = StatementPeriod(
            id = Ids.newId(),
            accountId = account.accountId,
            pocketCurrency = currency,
            periodFrom = from,
            periodTo = to,
            legalSequenceNumber = seq,
            electronicSequenceNumber = seq,
            openingBalance = opening,
            closingBalance = closing,
            entryCount = entries.size,
            closedAt = clock(),
            status = PeriodCloseStatus.CLOSED,
            supersedesSequence = standing.legalSequenceNumber,
            // The superseding page freezes its own render inputs, exactly as a first close does
            // (#3986). Without this the correction would be born with the defect it corrects.
            snapshot = StatementSnapshot(account.iban, account.holderName, entries),
        )
        periods.supersedeAndReplace(standing.id, replacement, periodRestatedEvent(account, replacement, standing))
    }

    /**
     * `account.statement.period.restated.v1` — a NEW event type, not a v2 of `period.closed`.
     * Consumers of `period.closed` keep their contract unchanged; a consumer that must react to a
     * correction opts in explicitly (a silently-widened `period.closed` would have downstream
     * systems treat a restatement as a first close).
     *
     * Issue #3994/#5256: `sourceService` lets `AuditConsumer` attribute the row from the producer's
     * own claim ([AttributionSource.EVENT]) instead of its topic-derived fallback, and `occurredAt`
     * (the fleet's canonical event-time key, mirrored from `closedAt` exactly as the two sibling
     * `period.closed`/`close_failed` payloads do) stops the audit row being stamped with ingest
     * time. Both siblings were patched by the fleet sweep; this third event type was missed because
     * it lives in its own service class rather than in `StatementService`/`CloseOrchestrator`.
     */
    private fun periodRestatedEvent(
        account: PocketAccountInfo,
        period: StatementPeriod,
        superseded: StatementPeriod,
    ): StatementOutboxMessage {
        val payload = """
            {"eventType":"account.statement.period.restated.v1",
            "accountId":"${account.accountId}",
            "iban":"${account.iban}",
            "pocketCurrency":"${period.pocketCurrency}",
            "periodFrom":"${period.periodFrom}",
            "periodTo":"${period.periodTo}",
            "legalSequenceNumber":${period.legalSequenceNumber},
            "electronicSequenceNumber":${period.electronicSequenceNumber},
            "supersedesSequence":${period.supersedesSequence},
            "openingBalance":${period.openingBalance.toPlainString()},
            "closingBalance":${period.closingBalance.toPlainString()},
            "supersededClosingBalance":${superseded.closingBalance.toPlainString()},
            "entryCount":${period.entryCount},
            "supersededEntryCount":${superseded.entryCount},
            "closedAt":"${period.closedAt}",
            "occurredAt":"${period.closedAt}",
            "sourceService":"statement-service"}
        """.trimIndent().replace("\n", "")
        return StatementOutboxMessage(
            eventId = Ids.newId(),
            aggregateId = period.id,
            eventType = "account.statement.period.restated.v1",
            payload = payload,
        )
    }
}

/**
 * Raised when a restatement is requested for a window that has no standing close. A correction is a
 * *replacement* of an issued legal statement page (ADR-0035 §D) — there is nothing to replace, and
 * minting a first close through this path would bypass the close orchestrator's accounting.
 */
class NoClosedPeriodToRestateException(
    val accountId: UUID,
    val currency: String,
    val from: LocalDate,
    val to: LocalDate,
) : RuntimeException("No closed statement period to restate for $accountId/$currency $from..$to")
