// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.openbank.statement.application.port.`in`.AdHocExportUseCase
import com.openbank.statement.application.port.`in`.ClosePeriodUseCase
import com.openbank.statement.application.port.`in`.ClosePocketUseCase
import com.openbank.statement.application.port.`in`.ListStatementsUseCase
import com.openbank.statement.application.port.`in`.RenderStatementUseCase
import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.BalancePort
import com.openbank.statement.application.port.out.BookedEntryPort
import com.openbank.statement.application.port.out.PocketAccountInfo
import com.openbank.statement.application.port.out.StatementOutboxMessage
import com.openbank.statement.application.port.out.StatementPeriodRepository
import com.openbank.statement.domain.model.BalanceAnchor
import com.openbank.statement.domain.model.PeriodCloseStatus
import com.openbank.statement.domain.model.StatementEntry
import com.openbank.statement.domain.model.StatementFormat
import com.openbank.statement.domain.model.StatementModel
import com.openbank.statement.domain.model.StatementPeriod
import com.openbank.statement.domain.reconcile.ReconciliationPolicy
import com.openbank.statement.domain.render.StatementRenderer
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Orchestrates the statement lifecycle (ADR-0035):
 *  - **period-close** (§F.1): read booked entries + the independent closing balance, reconcile
 *    *fail-closed* (§E), assign the next legal/electronic sequence, persist the small period record,
 *    emit `account.statement.period.closed`. No rendered bytes are produced or stored.
 *  - **render** (§F.2): replay a closed period deterministically into the requested format on demand.
 *  - **ad-hoc export** (§F.3): a non-sequenced informational export for an arbitrary range.
 *
 * The reconciliation/sequence/projection logic lives in the (framework-free) domain; this use case
 * only wires the ports together.
 */
@ApplicationScoped
class StatementService(
    private val accountInfo: AccountInfoPort,
    private val bookedEntries: BookedEntryPort,
    private val balance: BalancePort,
    private val periods: StatementPeriodRepository,
) : ClosePeriodUseCase,
    ClosePocketUseCase,
    RenderStatementUseCase,
    ListStatementsUseCase,
    AdHocExportUseCase {

    /** Clock seam: `closedAt` is stamped at close time and then *stored*, so renders stay deterministic
     *  (ADR-0035 §F). Overridable in tests; CDI uses the default. */
    internal var clock: () -> Instant = Instant::now

    override fun closeMonth(accountId: UUID, periodFrom: LocalDate, periodTo: LocalDate): Uni<List<StatementPeriod>> =
        accountInfo.pocketAccount(accountId).flatMap { account ->
            // Sequential per-pocket close (concatenate) — keeps legal-sequence assignment race-free.
            Multi.createFrom().iterable(account.currencies)
                .onItem().transformToUniAndConcatenate { ccy -> closePocket(account, ccy, periodFrom, periodTo) }
                .collect().asList()
        }

    override fun closePocketMonth(
        accountId: UUID,
        currency: String,
        from: LocalDate,
        to: LocalDate,
    ): Uni<StatementPeriod> =
        accountInfo.pocketAccount(accountId).flatMap { account -> closePocket(account, currency, from, to) }

    private fun closePocket(
        account: PocketAccountInfo,
        currency: String,
        from: LocalDate,
        to: LocalDate,
    ): Uni<StatementPeriod> = periods.findByPeriod(account.accountId, currency, from, to).flatMap { existing ->
        if (existing != null) {
            // Idempotent (ADR-0035 §F): a re-run returns the existing close, never a new sequence.
            Uni.createFrom().item(existing)
        } else {
            mintPeriod(account, currency, from, to)
        }
    }

    private fun mintPeriod(
        account: PocketAccountInfo,
        currency: String,
        from: LocalDate,
        to: LocalDate,
    ): Uni<StatementPeriod> = bookedEntries.bookedEntries(account.accountId, currency, from, to).flatMap { entries ->
        openingBalance(account.accountId, currency, from).flatMap { opening ->
            balance.closingBalance(account.accountId, currency, to).flatMap { reported ->
                val net = netMovementOf(entries)
                when (val r = ReconciliationPolicy.reconcile(opening, net, reported.amount)) {
                    is ReconciliationPolicy.Result.Mismatch ->
                        Uni.createFrom().failure(
                            ReconciliationException(account.accountId, currency, from, to, r),
                        )
                    is ReconciliationPolicy.Result.Reconciled ->
                        persistClose(account, currency, from, to, entries, opening, r.closingBalance)
                }
            }
        }
    }

    private fun persistClose(
        account: PocketAccountInfo,
        currency: String,
        from: LocalDate,
        to: LocalDate,
        entries: List<StatementEntry>,
        opening: BigDecimal,
        closing: BigDecimal,
    ): Uni<StatementPeriod> = periods.nextLegalSequence(account.accountId, currency).flatMap { seq ->
        val period = StatementPeriod(
            id = UUID.randomUUID(),
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
        )
        // Atomic: the period record and its `period.closed` outbox event commit together,
        // so a crash can't leave a closed period whose event was never emitted (was two
        // separate transactions via save(...) + outbox.append(...)).
        periods.saveWithOutbox(period, periodClosedEvent(account, period))
    }

    /** Opening balance = the prior close's closing, else balance-service's balance the day before. */
    private fun openingBalance(accountId: UUID, currency: String, from: LocalDate): Uni<BigDecimal> =
        periods.priorClosing(accountId, currency, from).flatMap { prior ->
            if (prior != null) {
                Uni.createFrom().item(prior)
            } else {
                balance.closingBalance(accountId, currency, from.minusDays(1)).map { it.amount }
            }
        }

    override fun render(
        accountId: UUID,
        currency: String,
        legalSequence: Long,
        format: StatementFormat,
    ): Uni<StatementRenderer.Rendered> = periods.findBySequence(accountId, currency, legalSequence).flatMap { period ->
        if (period == null) {
            Uni.createFrom().failure(StatementNotFoundException(accountId, currency, legalSequence))
        } else {
            accountInfo.pocketAccount(accountId).flatMap { account ->
                bookedEntries.bookedEntries(accountId, currency, period.periodFrom, period.periodTo)
                    .map { entries ->
                        StatementRenderer.render(modelFromPeriod(account, period, entries), format)
                    }
            }
        }
    }

    override fun export(
        accountId: UUID,
        currency: String,
        from: LocalDate,
        to: LocalDate,
        format: StatementFormat,
    ): Uni<StatementRenderer.Rendered> = accountInfo.pocketAccount(accountId).flatMap { account ->
        bookedEntries.bookedEntries(accountId, currency, from, to).flatMap { entries ->
            openingBalance(accountId, currency, from).map { opening ->
                val closing = opening.add(netMovementOf(entries))
                // Non-sequenced informational export (legal/electronic sequence = 0).
                val model = StatementModel(
                    accountId = accountId,
                    iban = account.iban,
                    currency = currency,
                    holderName = account.holderName,
                    periodFrom = from,
                    periodTo = to,
                    openingBalance = BalanceAnchor(opening, currency, from),
                    closingBalance = BalanceAnchor(closing, currency, to),
                    entries = entries,
                    legalSequenceNumber = 0,
                    electronicSequenceNumber = 0,
                    closedAt = clock(),
                )
                StatementRenderer.render(model, format)
            }
        }
    }

    override fun list(accountId: UUID): Uni<List<StatementPeriod>> = periods.listForAccount(accountId)

    private fun modelFromPeriod(
        account: PocketAccountInfo,
        period: StatementPeriod,
        entries: List<StatementEntry>,
    ): StatementModel = StatementModel(
        accountId = account.accountId,
        iban = account.iban,
        currency = period.pocketCurrency,
        holderName = account.holderName,
        periodFrom = period.periodFrom,
        periodTo = period.periodTo,
        openingBalance = BalanceAnchor(period.openingBalance, period.pocketCurrency, period.periodFrom),
        closingBalance = BalanceAnchor(period.closingBalance, period.pocketCurrency, period.periodTo),
        entries = entries,
        legalSequenceNumber = period.legalSequenceNumber,
        electronicSequenceNumber = period.electronicSequenceNumber,
        closedAt = period.closedAt,
        supersedesSequence = period.supersedesSequence,
    )

    private fun periodClosedEvent(account: PocketAccountInfo, period: StatementPeriod): StatementOutboxMessage {
        val payload = """
            {"eventType":"account.statement.period.closed.v1",
            "accountId":"${account.accountId}",
            "iban":"${account.iban}",
            "pocketCurrency":"${period.pocketCurrency}",
            "periodFrom":"${period.periodFrom}",
            "periodTo":"${period.periodTo}",
            "legalSequenceNumber":${period.legalSequenceNumber},
            "electronicSequenceNumber":${period.electronicSequenceNumber},
            "openingBalance":${period.openingBalance.toPlainString()},
            "closingBalance":${period.closingBalance.toPlainString()},
            "entryCount":${period.entryCount},
            "closedAt":"${period.closedAt}"}
        """.trimIndent().replace("\n", "")
        return StatementOutboxMessage(
            eventId = UUID.randomUUID(),
            aggregateId = period.id,
            eventType = "account.statement.period.closed.v1",
            payload = payload,
        )
    }
}

/** Raised when fail-closed reconciliation (ADR-0035 §E) blocks a period-close. */
class ReconciliationException(
    val accountId: UUID,
    val currency: String,
    val from: LocalDate,
    val to: LocalDate,
    val mismatch: ReconciliationPolicy.Result.Mismatch,
) : RuntimeException(
    "Statement reconciliation failed for $accountId/$currency $from..$to: " +
        "computed=${mismatch.computed} reported=${mismatch.reported} delta=${mismatch.delta}",
)

class StatementNotFoundException(accountId: UUID, currency: String, legalSequence: Long) :
    RuntimeException("No closed statement $legalSequence for $accountId/$currency")

/**
 * Thrown when an account in the registry is not viable for statement production:
 * empty IBAN (debris from a broken early-onboarding attempt) or no active currency pockets.
 * The close orchestrator counts these as SKIPPED rather than FAILED so the
 * StatementCloseFailures alert does not fire on data debris (issue #862).
 */
class NotViableAccountException(accountId: java.util.UUID, reason: String) :
    RuntimeException("Account $accountId is not viable for statement close: $reason")
