// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.close

import com.openbank.libs.close.AccountingDayClosed
import com.openbank.libs.close.CloseJobRun
import com.openbank.treasury.application.port.out.CloseJobRunPort
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** Job names recorded in `treasury_close_job_run` (ADR-0342 D1). Stable: they are persisted. */
object TreasuryCloseJobs {
    /** The hourly daily-interest accrual of SETTLED deals (`InterestAccrualScheduler`). */
    const val ACCRUAL = "accrual"

    /** The participant name treasury-service reports under; must match the ledger's declaration. */
    const val PARTICIPANT = "treasury"

    /**
     * Fixed refusal categories. A reason is stored in the ledger's participation history and shown on
     * the day-end page, so it carries a category and a count, never a deal id, a counterparty or an
     * exception message.
     */
    const val REASON_DAY_CLOSED = "ledger refused the posting: ACCOUNTING_DAY_CLOSED"
    const val REASON_DEAL_FAILED = "deal(s) failed to accrue"
    const val REASON_ACCRUAL_UNFINISHED = "deal(s) left unaccrued (pass budget reached)"
    const val REASON_PASS_FAILED = "accrual pass failed before completing"
    const val REASON_PASS_ABANDONED = "pass started and never completed"
    const val REASON_JOURNAL_EVENTS_DEAD = "journal event(s) never delivered (outbox gave up)"

    private const val HTTP_CONFLICT = 409

    /** Slack on top of the longest legitimate pass, for scheduling jitter and the final writes. */
    private const val STALE_MARGIN_MINUTES = 30L
    val STALE_MARGIN: Duration = Duration.ofMinutes(STALE_MARGIN_MINUTES)

    /**
     * How long an in-flight marker may stand before it means "the pass died". DERIVED, never
     * configured on its own (the #13708 lesson): the accrual stops starting deals once its
     * [passBudget] is spent, so a live pass lasts at most the budget plus one deal's postings, which
     * the margin covers. A separately configured threshold could sit below that and report a
     * still-running pass as abandoned.
     */
    fun staleAfter(passBudget: Duration): Duration = passBudget.plus(STALE_MARGIN)

    private val log = Logger.getLogger(TreasuryCloseJobs::class.java)

    /**
     * True when the ledger's closed-day 409 (ADR-0342 D4) is anywhere in [failure]'s cause chain. The
     * REST client surfaces it as a [WebApplicationException] carrying the ledger's response.
     */
    fun isClosedDayRejection(failure: Throwable): Boolean = generateSequence(failure) { it.cause }
        .filterIsInstance<WebApplicationException>()
        .any { e ->
            val status = e.response?.status ?: return@any false
            status == HTTP_CONFLICT &&
                (
                    AccountingDayClosed.isClosedDayRejection(status, bodyOf(e)) ||
                        AccountingDayClosed.isClosedDayRejection(status, e.message)
                    )
        }

    private fun bodyOf(e: WebApplicationException): String? = runCatching {
        e.response.readEntity(String::class.java)
    }.getOrNull()

    /**
     * Records one job's pass under [businessDate], the Prague accounting day the pass worked as of
     * (taken once at its start). Never fails the caller: a lost record only means the day is not
     * reported, which holds the ledger's close and pages, the safe direction.
     */
    fun record(
        runs: CloseJobRunPort,
        clock: Clock,
        businessDate: LocalDate,
        job: String,
        items: Int,
        refused: Int = 0,
        reason: String? = null,
    ): Uni<Unit> = runs.record(
        CloseJobRun(
            businessDate = businessDate,
            job = job,
            itemsProcessed = items,
            journalsPosted = 0,
            refused = refused,
            refusalReason = reason,
            completedAt = clock.instant(),
        ),
    ).onFailure().recoverWithItem { e ->
        log.warnf("treasury day-close evidence for job %s NOT recorded (%s)", job, e.javaClass.simpleName)
        Unit
    }
}
