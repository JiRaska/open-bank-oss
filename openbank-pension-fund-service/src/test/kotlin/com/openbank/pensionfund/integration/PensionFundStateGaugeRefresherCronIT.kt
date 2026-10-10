// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.pensionfund.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Drives the REAL schedule of the pension-fund state-gauge refresh (#12424; rules.yaml:
 * scheduled_methods): a direct call runs on the test thread, never on the scheduler's. Success is
 * read from the liveness gauge, recorded only after a snapshot was published, and the gauges must
 * then carry a series for every queue.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PensionFundStateGaugeRefresherCronIT.EverySecond::class)
class PensionFundStateGaugeRefresherCronIT {

    @Inject
    lateinit var registry: MeterRegistry

    class EverySecond : QuarkusTestProfile {
        // Literals only: a profile loads in another classloader.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.pension-fund.state-gauges.every" to "1s",
            "openbank.pension-fund.state-gauges.delay" to "0s",
        )
    }

    private fun recorded(): Double = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "pension-fund-state-gauges").gauge()?.value() ?: 0.0

    @Test
    fun `the state-gauge refresh succeeds on the real scheduler thread and publishes every queue`() {
        val deadline = System.nanoTime() + DEADLINE_NANOS
        while (System.nanoTime() < deadline && recorded() < 1.0) {
            Thread.sleep(POLL_MS)
        }
        assertThat(recorded()).isEqualTo(1.0)
        listOf("orders_pending", "navs_awaiting_approval", "strategy_changes_awaiting_approval").forEach { queue ->
            assertThat(registry.find("openbank.pension_fund.queue.oldest_age_seconds").tag("queue", queue).gauge())
                .describedAs("queue=%s", queue).isNotNull()
        }
    }

    private companion object {
        const val DEADLINE_NANOS = 20_000_000_000L
        const val POLL_MS = 200L
    }
}
