// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.close

import java.time.Instant
import java.time.LocalDate

/** One participation report. The ledger addresses it by `(businessDate, participant)`. */
data class CloseParticipationReport(
    val businessDate: LocalDate,
    val outcome: CloseOutcome,
    val journalsPosted: Int = 0,
    val itemsProcessed: Int = 0,
    val reason: String? = null,
) {
    init {
        require(journalsPosted >= 0 && itemsProcessed >= 0) { "counts must be non-negative" }
        require(outcome == CloseOutcome.RAN || !reason.isNullOrBlank()) { "$outcome requires a reason" }
        require(outcome == CloseOutcome.RAN || journalsPosted == 0) { "$outcome cannot have posted journals" }
    }
}

/**
 * The persisted outcome of ONE scheduled job's run for one accounting day, kept by the participant
 * in its own database. A participant with two jobs (interest: accrual daily, capitalization on the
 * 1st) records one row per job; [CloseParticipationAssembler] folds them into the day's report.
 */
data class CloseJobRun(
    val businessDate: LocalDate,
    val job: String,
    val itemsProcessed: Int,
    val journalsPosted: Int,
    val refused: Int,
    val refusalReason: String?,
    val completedAt: Instant,
)

/** Folds a participant's job runs for one day into the report it sends (ADR-0342 D1/D4). */
object CloseParticipationAssembler {

    /**
     * @param expectedJobs the jobs that were due on [businessDate]. A due job with no run means the
     *   participant has NOT finished the day, and the answer is `null` — nothing is reported, so the
     *   ledger keeps waiting and its stuck-CUTOFF alert pages. Reporting "ran" for a day whose job
     *   never ran is the exact defect this contract exists to remove.
     * @param skipReason when non-null and nothing was due, the day is reported SKIPPED with it.
     */
    fun assemble(
        businessDate: LocalDate,
        expectedJobs: Set<String>,
        runs: Collection<CloseJobRun>,
        skipReason: String? = null,
    ): CloseParticipationReport? {
        val forDay = runs.filter { it.businessDate == businessDate }.associateBy { it.job }
        if (expectedJobs.isEmpty()) {
            return CloseParticipationReport(
                businessDate = businessDate,
                outcome = CloseOutcome.SKIPPED,
                reason = skipReason ?: "no job was due on $businessDate",
            )
        }
        if (!forDay.keys.containsAll(expectedJobs)) return null
        val due = expectedJobs.mapNotNull { forDay[it] }
        val refused = due.filter { it.refused > 0 }
        if (refused.isNotEmpty()) {
            return CloseParticipationReport(
                businessDate = businessDate,
                outcome = CloseOutcome.REFUSED,
                itemsProcessed = due.sumOf { it.itemsProcessed },
                reason = refused.joinToString("; ") { run ->
                    "${run.job}: ${run.refused} posting(s) refused — ${run.refusalReason ?: "ledger refused the day"}"
                },
            )
        }
        return CloseParticipationReport(
            businessDate = businessDate,
            outcome = CloseOutcome.RAN,
            journalsPosted = due.sumOf { it.journalsPosted },
            itemsProcessed = due.sumOf { it.itemsProcessed },
        )
    }
}

/**
 * The ledger answers a posting into a day that no longer accepts postings with 409 and this code
 * in the body (ADR-0207 D3, ADR-0342 D4). A participant uses it to tell a closed-day refusal — a
 * close outcome — from any other 409.
 */
object AccountingDayClosed {
    const val ERROR_CODE = "ACCOUNTING_DAY_CLOSED"

    private const val HTTP_CONFLICT = 409

    fun isClosedDayRejection(status: Int, body: String?): Boolean =
        status == HTTP_CONFLICT && body?.contains(ERROR_CODE) == true
}
