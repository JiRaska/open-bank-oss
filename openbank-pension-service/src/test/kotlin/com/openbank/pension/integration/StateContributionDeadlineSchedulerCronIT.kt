// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.pension.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Drives the REAL cron of the CZ state-contribution deadline job (#12382; rules.yaml:
 * scheduled_methods). A direct call would supply the Vert.x context the scheduler thread does not
 * have. Success is read from the liveness gauge, which is recorded only on the success branch, and
 * the deadline gauges must have been registered at startup.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(StateContributionDeadlineSchedulerCronIT.EverySecond::class)
class StateContributionDeadlineSchedulerCronIT {

    @Inject
    lateinit var registry: MeterRegistry

    class EverySecond : QuarkusTestProfile {
        // Literals only: a profile loads in another classloader.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.pension.state-contribution.cz.deadline-cron" to "* * * * * ?",
        )
    }

    private fun recorded(): Double = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "pension-state-contribution-deadlines").gauge()?.value() ?: 0.0

    @Test
    fun `the deadline job succeeds on the real cron thread and publishes its breach gauges`() {
        val deadline = System.nanoTime() + DEADLINE_NANOS
        while (System.nanoTime() < deadline && recorded() < 1.0) {
            Thread.sleep(POLL_MS)
        }
        assertThat(recorded()).isEqualTo(1.0)
        listOf("claim_filing", "claim_payment", "return_due").forEach { kind ->
            assertThat(registry.find("openbank_pension_state_contribution_deadline_breaches").tag("kind", kind).gauge())
                .describedAs("gauge kind=%s", kind).isNotNull()
        }
    }

    private companion object {
        const val DEADLINE_NANOS = 20_000_000_000L
        const val POLL_MS = 200L
    }
}
