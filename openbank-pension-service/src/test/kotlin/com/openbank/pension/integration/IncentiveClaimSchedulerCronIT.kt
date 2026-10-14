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
 * Drives the REAL cron (not a direct call, which would supply the Vert.x context the scheduler
 * does not — rules.yaml: scheduled_methods). Success is read from the liveness gauge the jobs
 * record ONLY on their success branch, so a job that throws on the cron thread leaves it at 0.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(IncentiveClaimSchedulerCronIT.EverySecond::class)
class IncentiveClaimSchedulerCronIT {

    @Inject
    lateinit var registry: MeterRegistry

    class EverySecond : QuarkusTestProfile {
        // Literals only: a profile loads in another classloader (CLAUDE.md, scheduler test footgun).
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // %test disables the scheduler fleet-wide (S5 added it at integration); this IT exists
            // to drive the REAL cron thread, so it re-enables it (root CLAUDE.md scheduler bullet).
            "quarkus.scheduler.enabled" to "true",
            "openbank.pension.incentive-claim-cron" to "* * * * * ?",
            "openbank.pension.subscription-sweep-cron" to "* * * * * ?",
        )
    }

    private fun recorded(workflow: String): Double = registry.find(
        WorkflowLivenessMetrics.SUCCESS_RECORDED,
    ).tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow).gauge()?.value()
        ?: 0.0

    @Test
    fun `both scheduled jobs succeed on the real cron thread`() {
        val deadline = System.nanoTime() + DEADLINE_NANOS
        while (System.nanoTime() < deadline &&
            (recorded("pension-incentive-claims") < 1.0 || recorded("pension-subscription-sweep") < 1.0)
        ) {
            Thread.sleep(POLL_MS)
        }
        assertThat(recorded("pension-incentive-claims")).isEqualTo(1.0)
        assertThat(recorded("pension-subscription-sweep")).isEqualTo(1.0)
    }

    private companion object {
        const val DEADLINE_NANOS = 20_000_000_000L
        const val POLL_MS = 200L
    }
}
