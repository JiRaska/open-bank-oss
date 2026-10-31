// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.infrastructure.schedule.AccountingDayScheduler
import com.openbank.ledger.infrastructure.schedule.FxRevaluationScheduler
import com.openbank.ledger.infrastructure.schedule.TieOutFreshnessWatchdog
import com.openbank.ledger.infrastructure.schedule.TieOutScheduler
import com.openbank.ledger.it.PostgresTestResource
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The four cron-driven ledger jobs whose trigger an instance may switch off (ADR-0337). */
internal val SWITCHABLE_WORKFLOWS = listOf(
    TieOutScheduler.WORKFLOW_NAME,
    TieOutFreshnessWatchdog.WORKFLOW_NAME,
    FxRevaluationScheduler.WORKFLOW_NAME,
    AccountingDayScheduler.WORKFLOW_NAME,
)

internal fun MeterRegistry.livenessAge(workflow: String): Double? =
    find(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow)
        .gauge()
        ?.value()

/**
 * A job whose cron is `off` registers NO workflow-liveness heartbeat (ADR-0337, #12500).
 *
 * The pension company's ledger instance switches every bank-only scheduler off. Before this, each
 * still registered `openbank_workflow_last_success_age_seconds` at StartupEvent, seeded at boot and
 * never reset (the job cannot run, so it never records a success) — so `WorkflowLivenessStale`
 * (`age > 2 * expected_interval`) fired on that instance 2h after every start for the hourly
 * freshness watchdog and 2 days after for the daily ones, for jobs it deliberately does not run.
 *
 * Values, not presence: the journal-partition job stays ON in this profile and its gauge must be
 * present and read as this pod's uptime (seconds, cold-pod safe), so a registry that dropped every
 * gauge cannot pass this as "nothing registered".
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(SwitchedOffSchedulerLivenessIT.AllCronsOffProfile::class)
class SwitchedOffSchedulerLivenessIT {

    class AllCronsOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.ledger.tieout.cron" to "off",
            "openbank.ledger.tieout.freshness-cron" to "off",
            "openbank.ledger.fx-revaluation.cron" to "off",
            "openbank.ledger.accounting-day.cron" to "disabled",
        )
    }

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    fun `a switched-off job registers no liveness series`() {
        SWITCHABLE_WORKFLOWS.forEach { workflow ->
            assertThat(registry.livenessAge(workflow))
                .describedAs("$workflow is switched off and must not publish a heartbeat that can only go stale")
                .isNull()
            assertThat(
                registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                    .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow).gauge(),
            ).isNull()
        }
    }

    @Test
    fun `a job left on still registers, seeded at boot`() {
        val age = registry.livenessAge("ledger-journal-partition-maintenance")
        assertThat(age).describedAs("the partition job is ON here and must keep its heartbeat").isNotNull()
        assertThat(age!!).isBetween(0.0, MAX_COLD_POD_AGE_SECONDS)
    }

    private companion object {
        /** A fresh test JVM; generous for a slow runner, decades below an EPOCH seed. */
        const val MAX_COLD_POD_AGE_SECONDS = 900.0
    }
}

/** Control: with no switch set (the bank instance), every job keeps its boot-seeded heartbeat. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class DefaultSchedulerLivenessIT {

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    fun `every switchable job registers its heartbeat when its cron is on`() {
        SWITCHABLE_WORKFLOWS.forEach { workflow ->
            val age = registry.livenessAge(workflow)
            assertThat(age).describedAs("$workflow must register on the bank instance").isNotNull()
            assertThat(age!!).isBetween(0.0, 900.0)
        }
    }
}
