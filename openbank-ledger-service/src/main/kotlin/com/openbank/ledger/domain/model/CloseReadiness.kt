// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Immutable participation evidence captured inside the transaction that tied the day out. */
data class ClosedDayParticipation(
    val businessDate: LocalDate,
    val observedAt: Instant,
    val mode: String,
    val expectedPrincipals: Map<String, String>,
    val reports: List<ClosedParticipantReport>,
    val expectedJobs: Map<String, Set<String>>,
    val tieOutRunId: UUID?,
    val postingsUnchanged: Boolean = true,
    val sourceEvidenceUnchanged: Boolean = true,
)

data class ClosedParticipantReport(
    val participant: String,
    val outcome: String,
    val reportedBy: String,
    val reason: String?,
    val exercisedJobs: Set<String> = emptySet(),
    val sourceRunId: String? = null,
    val receiptSeq: Long? = null,
    val reportedAt: Instant? = null,
)

data class CloseReadinessDay(val businessDate: LocalDate, val blockers: List<String>)

/** Coverage of a whole calendar month, including its first-day capitalization and fee cycle. */
data class CloseReadiness(
    val month: YearMonth,
    val expectedParticipants: List<String>,
    val observedDays: Int,
    val requiredDays: Int,
    val eligibleForEnforcement: Boolean,
    val blockers: List<String>,
    val days: List<CloseReadinessDay>,
) {
    companion object {
        fun evaluate(
            month: YearMonth,
            today: LocalDate,
            expectedPrincipals: Map<String, String>,
            evidence: List<ClosedDayParticipation>,
            requiredJobs: Map<String, Set<String>>,
        ): CloseReadiness {
            requireValid(month < YearMonth.from(today)) { "month must be a completed calendar month" }
            val byDate = evidence.filter { YearMonth.from(it.businessDate) == month }.associateBy { it.businessDate }
            val days = (1..month.lengthOfMonth()).map { day ->
                val date = month.atDay(day)
                val snapshot = byDate[date]
                CloseReadinessDay(
                    date,
                    if (snapshot ==
                        null
                    ) {
                        listOf("NO_CLOSE_EVIDENCE")
                    } else {
                        snapshot.blockers(expectedPrincipals, requiredJobs)
                    },
                )
            }
            val blockers = buildList {
                if (expectedPrincipals.isEmpty()) add("NO_DECLARED_PARTICIPANTS")
                if (month != YearMonth.from(today).minusMonths(1)) add("STALE_PERIOD")
                if (days.any { it.blockers.isNotEmpty() }) add("INCOMPLETE_DAILY_EVIDENCE")
                if (requiredJobs.keys != expectedPrincipals.keys || requiredJobs.values.any { it.isEmpty() }) {
                    add("JOB_REQUIREMENTS_INCOMPLETE")
                }
                addAll(missingJobEvidence(byDate.values, expectedPrincipals, requiredJobs))
            }
            return CloseReadiness(
                month,
                expectedPrincipals.keys.sorted(),
                byDate.size,
                month.lengthOfMonth(),
                blockers.isEmpty(),
                blockers,
                days,
            )
        }

        private fun missingJobEvidence(
            snapshots: Collection<ClosedDayParticipation>,
            principals: Map<String, String>,
            requiredJobs: Map<String, Set<String>>,
        ): List<String> {
            val valid = snapshots.filter { it.blockers(principals, requiredJobs).isEmpty() }
            return requiredJobs.toSortedMap().flatMap { (participant, jobs) ->
                val completed = valid.flatMap { it.reports }.filter {
                    it.participant == participant && it.outcome == ParticipationOutcome.RAN.name
                }.flatMap { it.exercisedJobs }.toSet()
                (jobs - completed).sorted().map { "NO_SUCCESSFUL_JOB:$participant:$it" }
            }
        }

        private fun ClosedDayParticipation.integrityBlockers(): List<String> = buildList {
            if (tieOutRunId == null) add("NO_POST_CUTOFF_TIEOUT")
            if (!postingsUnchanged) add("JOURNALS_CHANGED_AFTER_CLOSE")
            if (!sourceEvidenceUnchanged) add("SOURCE_EVIDENCE_CHANGED_AFTER_CLOSE")
        }

        private fun ClosedDayParticipation.blockers(
            expected: Map<String, String>,
            jobs: Map<String, Set<String>>,
        ): List<String> = buildList {
            addAll(integrityBlockers())
            if (expectedJobs != jobs) add("JOB_REQUIREMENTS_CHANGED")
            if (mode !in setOf("shadow", "enforce")) add("CHECK_DISABLED")
            if (expectedPrincipals != expected) add("PARTICIPANTS_CHANGED")
            val latest = reports.associateBy { it.participant }
            for ((name, principal) in expected.toSortedMap()) {
                val report = latest[name]
                when {
                    report == null -> add("MISSING:$name")
                    report.outcome == ParticipationOutcome.WAIVED.name -> add("WAIVED:$name")
                    report.reportedBy != principal -> add("PRINCIPAL_CHANGED:$name")
                    report.outcome == ParticipationOutcome.REFUSED.name -> add("REFUSED:$name")
                    report.outcome == ParticipationOutcome.SKIPPED.name && report.reason.isNullOrBlank() ->
                        add("UNEXPLAINED_SKIP:$name")
                    report.outcome !in setOf(ParticipationOutcome.RAN.name, ParticipationOutcome.SKIPPED.name) ->
                        add("UNKNOWN_OUTCOME:$name")
                }
            }
        }
    }
}
