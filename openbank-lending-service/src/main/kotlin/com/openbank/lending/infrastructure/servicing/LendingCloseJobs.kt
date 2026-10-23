// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.libs.close.AccountingDayClosed
import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate

/** Job names recorded in `lending_close_job_run` (ADR-0342 D1). Stable: they are persisted. */
object LendingCloseJobs {
    const val ACCRUAL = "accrual"
    const val PROVISIONING = "provisioning"

    /** The participant name lending-service reports under; must match the ledger's declaration. */
    const val PARTICIPANT = "lending"

    /**
     * Fixed refusal categories. A reason is stored in the ledger's participation history and shown
     * on the day-end page, so it carries a category and a count, never a loan id or an exception
     * message (the same bound interest-service keeps on its close evidence).
     */
    const val REASON_DAY_CLOSED = "ledger refused the posting: ACCOUNTING_DAY_CLOSED"
    const val REASON_ACCRUAL_FAILED = "accrual pass failed before completing"
    const val REASON_ACCRUAL_TRUNCATED = "due installment(s) left unaccrued"
    const val REASON_PROVISIONING_FAILED = "provisioning pass failed before completing"
    const val REASON_PROVISIONING_INCOMPLETE = "eligible exposure(s) left unprovisioned"
    const val REASON_PROVISIONING_UNCOUNTED = "provisioning coverage could not be counted"

    private const val HTTP_CONFLICT = 409
    private val log = Logger.getLogger(LendingCloseJobs::class.java)

    /**
     * True when the ledger's closed-day 409 (ADR-0342 D4) is anywhere in [failure]'s cause chain.
     * The REST client surfaces it as a [WebApplicationException] carrying the ledger's response;
     * the fault-tolerance guard rethrows the last attempt's exception unchanged.
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
     * Records one job's run under [businessDate] — the accounting day (Europe/Prague, ADR-0207) the
     * pass itself worked as of, taken once at the start of the pass, so a pass that crosses midnight
     * is filed under the day it accrued for, never the UTC date or the day it happened to finish.
     * Never fails the caller: a lost record only means the day is not reported, which holds the
     * ledger's close and pages, the safe direction.
     */
    fun record(
        runs: CloseJobRunPort,
        clock: Clock,
        businessDate: LocalDate,
        job: String,
        items: Int,
        journals: Int,
        refused: Int = 0,
        reason: String? = null,
    ): Uni<Unit> = runs.record(
        CloseJobRun(
            businessDate = businessDate,
            job = job,
            itemsProcessed = items,
            journalsPosted = journals,
            refused = refused,
            refusalReason = reason,
            completedAt = clock.instant(),
        ),
    ).onFailure().recoverWithItem { e ->
        log.warnf("lending day-close evidence for job %s NOT recorded (%s)", job, e.javaClass.simpleName)
        Unit
    }
}
