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
 * Drives the REAL schedule of the pension state-gauge refresh (#12424; rules.yaml:
 * scheduled_methods): a direct call runs on the test thread, never on the scheduler's. Success is
 * read from the liveness gauge, recorded only after a snapshot was published, and the gauges must
 * then carry series for every operational queue.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PensionStateGaugeRefresherCronIT.EverySecond::class)
class PensionStateGaugeRefresherCronIT {

    @Inject
    lateinit var registry: MeterRegistry

    class EverySecond : QuarkusTestProfile {
        // Literals only: a profile loads in another classloader.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.pension.state-gauges.every" to "1s",
            "openbank.pension.state-gauges.delay" to "0s",
        )
    }

    private fun recorded(): Double = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "pension-state-gauges").gauge()?.value() ?: 0.0

    @Test
    fun `the state-gauge refresh succeeds on the real scheduler thread and publishes every queue`() {
        val deadline = System.nanoTime() + DEADLINE_NANOS
        while (System.nanoTime() < deadline && recorded() < 1.0) {
            Thread.sleep(POLL_MS)
        }
        assertThat(recorded()).isEqualTo(1.0)
        listOf(
            "unmatched_payments",
            "payment_instructions_pending",
            "incentive_claims_pending",
            "state_contribution_returns_due",
        ).forEach { queue ->
            assertThat(registry.find("openbank.pension.queue.oldest_age_seconds").tag("queue", queue).gauge())
                .describedAs("queue=%s", queue).isNotNull()
        }
    }

    private companion object {
        const val DEADLINE_NANOS = 20_000_000_000L
        const val POLL_MS = 200L
    }
}
