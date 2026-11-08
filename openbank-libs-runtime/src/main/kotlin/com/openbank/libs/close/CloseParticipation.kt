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
    /** Successful persisted job receipts with observed work; an empty sweep cannot qualify a cycle. */
    val exercisedJobs: Set<String> = emptySet(),
    /** Stable identity of persisted source receipts; retransmission must not advance ledger work time. */
    val sourceRunId: String? = null,
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

    /** Revisit the entire period eligible for monthly readiness, preserving a longer recovery window. */
    fun reportingFrom(today: LocalDate, lookbackDays: Long): LocalDate {
        require(lookbackDays > 0) { "lookbackDays must be positive" }
        return minOf(today.minusDays(lookbackDays), today.withDayOfMonth(1).minusMonths(1))
    }

    /** An unfinished source must revoke an earlier positive report, even before another journal exists. */
    fun incomplete(
        businessDate: LocalDate,
        reason: String,
        sourceIdentity: Collection<String>,
    ): CloseParticipationReport = CloseParticipationReport(
        businessDate = businessDate,
        outcome = CloseOutcome.REFUSED,
        reason = reason,
        sourceRunId = sourceId(listOf(businessDate, "incomplete", reason) + sourceIdentity.sorted()),
    )

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
                sourceRunId = sourceId(listOf(businessDate, skipReason)),
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
                sourceRunId = sourceId(listOf(businessDate) + due.sortedBy { it.job }),
            )
        }
        return CloseParticipationReport(
            businessDate = businessDate,
            outcome = CloseOutcome.RAN,
            journalsPosted = due.sumOf { it.journalsPosted },
            itemsProcessed = due.sumOf { it.itemsProcessed },
            exercisedJobs = due.filter { it.itemsProcessed > 0 || it.journalsPosted > 0 }.map { it.job }.toSet(),
            sourceRunId = sourceId(listOf(businessDate) + due.sortedBy { it.job }),
        )
    }

    private fun sourceId(values: List<Any?>): String {
        val canonical = values.joinToString("") { value -> value?.toString()?.let { "${it.length}:$it" } ?: "-1:" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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
