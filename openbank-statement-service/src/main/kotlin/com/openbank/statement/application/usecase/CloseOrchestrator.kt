// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.openbank.statement.application.port.`in`.ClosePocketUseCase
import com.openbank.statement.application.port.`in`.CloseRunQueryUseCase
import com.openbank.statement.application.port.`in`.RunCloseUseCase
import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.AccountRegistry
import com.openbank.statement.application.port.out.CloseMetricsPort
import com.openbank.statement.application.port.out.CloseRunRepository
import com.openbank.statement.application.port.out.StatementOutbox
import com.openbank.statement.application.port.out.StatementOutboxMessage
import com.openbank.statement.application.port.out.StatementPeriodRepository
import com.openbank.statement.domain.close.CloseCalendar
import com.openbank.statement.domain.model.CloseFailure
import com.openbank.statement.domain.model.CloseFailureReason
import com.openbank.statement.domain.model.CloseRun
import com.openbank.statement.domain.model.CloseRunStatus
import com.openbank.statement.domain.model.CloseTrigger
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a full self-healing close pass (ADR-0069 D3 / issue #470) and records a durable outcome.
 *
 * For every account in the local registry, for every pocket, it closes each month still owed
 * (computed by [CloseCalendar]) up to the prior month. A pocket failure is **isolated**: it is
 * counted, persisted as a [CloseFailure], emitted as a `period.close_failed` event, and metered —
 * the run continues and converges instead of aborting on the first error (the old scheduler swallowed
 * failures silently). The run record + counts are what the monthly-cron go/no-go decision reads.
 */
@ApplicationScoped
class CloseOrchestrator(
    private val accountRegistry: AccountRegistry,
    private val accountInfo: AccountInfoPort,
    private val periods: StatementPeriodRepository,
    private val closePocket: ClosePocketUseCase,
    private val runs: CloseRunRepository,
    private val outbox: StatementOutbox,
    private val metrics: CloseMetricsPort,
    private val wallClock: Clock,
) : RunCloseUseCase,
    CloseRunQueryUseCase {

    private val log = Logger.getLogger(CloseOrchestrator::class.java)

    // Clock seam (overridable in tests); CDI defaults to the injected fleet Clock — the
    // #1302 fix. `LocalDate::now` (JVM-default zone) was the third clock regime in the
    // closing audit: a pod whose default zone is not the fleet's closes a different
    // accounting day than the cron intended. `LocalDate.now(wallClock)` pins the day to
    // the same authority every other service reads (ADR-0100's injected Clock).
    internal var clock: () -> Instant = { Instant.now(wallClock) }
    internal var today: () -> LocalDate = { LocalDate.now(wallClock) }

    override fun runClose(trigger: CloseTrigger): Uni<CloseRun> {
        val (from, to) = CloseCalendar.priorMonthBounds(today())
        val runId = UUID.randomUUID()
        val started = clock()
        val accounts = AtomicInteger()
        val closed = AtomicInteger()
        val failed = AtomicInteger()
        val skipped = AtomicInteger()

        val run = CloseRun(
            id = runId, trigger = trigger, status = CloseRunStatus.RUNNING,
            periodFrom = from, periodTo = to,
            accountsEnumerated = 0, pocketsClosed = 0, pocketsFailed = 0, pocketsSkipped = 0,
            startedAt = started, finishedAt = null,
        )

        return runs.startRun(run)
            .flatMap { accountRegistry.allAccountIds() }
            .flatMap { ids ->
                log.infof("Close run %s (%s) enumerating %d account(s) through %s", runId, trigger, ids.size, to)
                Multi.createFrom().iterable(ids)
                    .onItem().transformToUniAndConcatenate { accountId ->
                        accounts.incrementAndGet()
                        closeAccount(runId, accountId, to, closed, failed, skipped)
                    }
                    .collect().asList()
            }
            .flatMap {
                val status = if (failed.get() > 0) CloseRunStatus.COMPLETED_WITH_FAILURES else CloseRunStatus.COMPLETED
                val finished = run.copy(
                    status = status,
                    accountsEnumerated = accounts.get(),
                    pocketsClosed = closed.get(),
                    pocketsFailed = failed.get(),
                    pocketsSkipped = skipped.get(),
                    finishedAt = clock(),
                )
                metrics.runFinished(status)
                log.infof(
                    "Close run %s %s: accounts=%d closed=%d failed=%d skipped=%d",
                    runId,
                    status,
                    accounts.get(),
                    closed.get(),
                    failed.get(),
                    skipped.get(),
                )
                runs.finishRun(finished)
            }
    }

    private fun closeAccount(
        runId: UUID,
        accountId: UUID,
        throughMonthEnd: LocalDate,
        closed: AtomicInteger,
        failed: AtomicInteger,
        skipped: AtomicInteger,
    ): Uni<Unit> = accountInfo.pocketAccount(accountId)
        .onItem().transformToUni { account ->
            Multi.createFrom().iterable(account.currencies)
                .onItem().transformToUniAndConcatenate { ccy ->
                    closePocketCatchUp(runId, accountId, ccy, throughMonthEnd, closed, failed, skipped)
                }
                .collect().asList().replaceWith(Unit)
        }
        .onFailure().recoverWithUni { e ->
            val reason = classify(e)
            val (from, to) = CloseCalendar.priorMonthBounds(today())
            if (reason == CloseFailureReason.NOT_VIABLE) {
                // Debris account (empty IBAN / no balance record): count as skipped,
                // not failed, so StatementCloseFailures alert does not fire (#862).
                skipped.incrementAndGet()
                metrics.pocketSkipped()
                log.infof("Close skipped for account %s (NOT_VIABLE): %s", accountId, e.message)
                Uni.createFrom().item(Unit)
            } else {
                failed.incrementAndGet()
                recordFailure(runId, accountId, "?", from, to, reason, e).replaceWith(Unit)
            }
        }

    private fun closePocketCatchUp(
        runId: UUID,
        accountId: UUID,
        currency: String,
        throughMonthEnd: LocalDate,
        closed: AtomicInteger,
        failed: AtomicInteger,
        skipped: AtomicInteger,
    ): Uni<Unit> = periods.latestClosedPeriodTo(accountId, currency).flatMap { last ->
        val windows = CloseCalendar.monthsToClose(last, throughMonthEnd)
        if (windows.isEmpty()) {
            skipped.incrementAndGet()
            metrics.pocketSkipped()
            return@flatMap Uni.createFrom().item(Unit)
        }
        Multi.createFrom().iterable(windows)
            .onItem().transformToUniAndConcatenate { (from, to) ->
                closePocket.closePocketMonth(accountId, currency, from, to)
                    .onItem().transformToUni { _ ->
                        closed.incrementAndGet()
                        metrics.pocketClosed()
                        Uni.createFrom().item(Unit)
                    }
                    .onFailure().recoverWithUni { e ->
                        failed.incrementAndGet()
                        val reason = classify(e)
                        metrics.pocketFailed(reason)
                        log.errorf(e, "Close failed for %s/%s %s..%s (%s)", accountId, currency, from, to, reason)
                        recordFailure(runId, accountId, currency, from, to, reason, e)
                            .flatMap { emitCloseFailed(accountId, currency, from, to, reason, e) }
                            .replaceWith(Unit)
                    }
            }
            .collect().asList().replaceWith(Unit)
    }

    private fun recordFailure(
        runId: UUID,
        accountId: UUID,
        currency: String,
        from: LocalDate,
        to: LocalDate,
        reason: CloseFailureReason,
        e: Throwable,
    ): Uni<CloseFailure> = runs.recordFailure(
        CloseFailure(
            id = UUID.randomUUID(), runId = runId, accountId = accountId, pocketCurrency = currency,
            periodFrom = from, periodTo = to, reason = reason,
            detail = (e.message ?: e.javaClass.simpleName).take(2000), failedAt = clock(),
        ),
    )

    private fun emitCloseFailed(
        accountId: UUID,
        currency: String,
        from: LocalDate,
        to: LocalDate,
        reason: CloseFailureReason,
        e: Throwable,
    ): Uni<Void> {
        val detail = jsonEscape((e.message ?: e.javaClass.simpleName).take(500))
        val payload = """
            {"eventType":"account.statement.period.close_failed.v1",
            "accountId":"$accountId",
            "pocketCurrency":"$currency",
            "periodFrom":"$from",
            "periodTo":"$to",
            "reason":"$reason",
            "detail":"$detail",
            "failedAt":"${clock()}"}
        """.trimIndent().replace("\n", "")
        return outbox.append(
            StatementOutboxMessage(
                eventId = UUID.randomUUID(),
                aggregateId = accountId,
                eventType = "account.statement.period.close_failed.v1",
                payload = payload,
            ),
        )
    }

    /** Escape a free-text value for safe embedding in the hand-built JSON outbox payload. */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Classify a failure for retry expectation and alerting eligibility. */
    private fun classify(e: Throwable): CloseFailureReason = when {
        e is ReconciliationException -> CloseFailureReason.RECONCILIATION
        e is NotViableAccountException -> CloseFailureReason.NOT_VIABLE
        isUpstream(e) -> CloseFailureReason.UPSTREAM
        else -> CloseFailureReason.UNKNOWN
    }

    private fun isUpstream(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            val n = c.javaClass.name
            if (n.contains("ClientWebApplicationException") ||
                n.contains("ConnectException") ||
                n.contains("WebApplicationException") ||
                n.contains("TimeoutException")
            ) {
                return true
            }
            c = c.cause
        }
        return false
    }

    // ---- read side (operator surface) ---------------------------------------------------------
    override fun latestRun(): Uni<CloseRun?> = runs.latestRun()
    override fun recentRuns(limit: Int): Uni<List<CloseRun>> = runs.recentRuns(limit)
    override fun failuresForRun(runId: UUID): Uni<List<CloseFailure>> = runs.failuresForRun(runId)
}
