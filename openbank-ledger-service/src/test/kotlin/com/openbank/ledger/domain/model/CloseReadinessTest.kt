// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class CloseReadinessTest {
    private val month = YearMonth.of(2024, 2)
    private val today = LocalDate.of(2024, 3, 5)
    private val jobs = mapOf("interest" to setOf("accrual", "capitalization"), "billing" to setOf("billing-cycle"))
    private val principals = mapOf("interest" to "interest-account", "billing" to "billing-account")

    private fun evidence() = (1..month.lengthOfMonth()).map { day ->
        ClosedDayParticipation(
            month.atDay(day),
            Instant.parse("2024-03-01T06:00:00Z"),
            "shadow",
            principals,
            principals.map { (name, principal) ->
                ClosedParticipantReport(name, "RAN", principal, null, jobs.getValue(name))
            },
            jobs,
            java.util.UUID.randomUUID(),
        )
    }

    private fun evaluate(rows: List<ClosedDayParticipation>) =
        CloseReadiness.evaluate(month, today, principals, rows, jobs)

    @Test
    fun `a complete leap month qualifies without a zero-counter assumption`() {
        val answer = evaluate(evidence())
        assertThat(answer.requiredDays).isEqualTo(29)
        assertThat(answer.observedDays).isEqualTo(29)
        assertThat(answer.eligibleForEnforcement).isTrue()
    }

    @Test
    fun `no observations and a missing day fail closed`() {
        assertThat(evaluate(emptyList()).eligibleForEnforcement).isFalse()
        val missing = evaluate(evidence().drop(1))
        assertThat(missing.days.first().blockers).containsExactly("NO_CLOSE_EVIDENCE")
        assertThat(missing.eligibleForEnforcement).isFalse()
    }

    @Test
    fun `missing first-day fees remain missing in the immutable close snapshot`() {
        val rows = evidence().toMutableList()
        rows[0] = rows[0].copy(reports = rows[0].reports.filter { it.participant != "billing" })
        assertThat(evaluate(rows).days.first().blockers).containsExactly("MISSING:billing")
        assertThat(evaluate(rows).eligibleForEnforcement).isFalse()
    }

    @Test
    fun `waivers and refused work are visible exceptions and cannot qualify promotion`() {
        for (outcome in listOf("WAIVED", "REFUSED")) {
            val rows = evidence().toMutableList()
            rows[0] =
                rows[0].copy(
                    reports = listOf(
                        ClosedParticipantReport(
                            "billing",
                            outcome,
                            if (outcome ==
                                "WAIVED"
                            ) {
                                "op"
                            } else {
                                "billing-account"
                            },
                            "INC-1",
                        ),
                    ),
                )
            assertThat(evaluate(rows).days.first().blockers).contains("$outcome:billing")
            assertThat(evaluate(rows).eligibleForEnforcement).isFalse()
        }
    }

    @Test
    fun `a month of disabled billing jobs is not a tested fee cycle`() {
        val rows = evidence().map { snapshot ->
            snapshot.copy(
                reports = snapshot.reports.map {
                    if (it.participant == "billing") it.copy(outcome = "SKIPPED", reason = "disabled") else it
                },
            )
        }
        assertThat(evaluate(rows).blockers).contains("NO_SUCCESSFUL_JOB:billing:billing-cycle")
    }

    @Test
    fun `disabled checks changed participants and changed identities invalidate the evidence`() {
        for (changed in listOf(
            evidence().first().copy(mode = "off"),
            evidence().first().copy(expectedPrincipals = principals + ("treasury" to "treasury-account")),
            evidence().first().copy(reports = listOf(ClosedParticipantReport("billing", "RAN", "foreign", null))),
        )) {
            assertThat(evaluate(listOf(changed) + evidence().drop(1)).eligibleForEnforcement).isFalse()
        }
    }

    @Test
    fun `daily interest success without capitalization cannot qualify a monthly cycle`() {
        val rows = evidence().map { snapshot ->
            snapshot.copy(
                reports = snapshot.reports.map {
                    if (it.participant == "interest") it.copy(exercisedJobs = setOf("accrual")) else it
                },
            )
        }
        assertThat(evaluate(rows).blockers).contains("NO_SUCCESSFUL_JOB:interest:capitalization")
        assertThat(evaluate(rows).eligibleForEnforcement).isFalse()
    }

    @Test
    fun `manual tie-out without a post-cutoff reconciliation cannot qualify`() {
        val rows = evidence().map { it.copy(tieOutRunId = null) }
        assertThat(evaluate(rows).days.first().blockers).contains("NO_POST_CUTOFF_TIEOUT")
        assertThat(evaluate(rows).eligibleForEnforcement).isFalse()
    }

    @Test
    fun `current or future months cannot qualify and old complete months are stale`() {
        assertThatThrownBy { CloseReadiness.evaluate(YearMonth.from(today), today, principals, emptyList(), jobs) }
            .isInstanceOf(LedgerValidationException::class.java)
        val old = CloseReadiness.evaluate(month, today.plusMonths(1), principals, evidence(), jobs)
        assertThat(old.blockers).contains("STALE_PERIOD")
        assertThat(old.eligibleForEnforcement).isFalse()
    }
}
