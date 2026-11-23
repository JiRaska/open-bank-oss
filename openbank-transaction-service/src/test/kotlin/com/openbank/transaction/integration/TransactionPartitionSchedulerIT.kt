// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.transaction.integration

import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Drives the REAL `@Scheduled` trigger of TransactionPartitionMaintainer (shrunk to 1s) and
 * waits for its liveness success. Calling `maintain()` directly would supply the Vert.x context
 * the scheduler does not, so only this proves the scheduled path can run its reactive DDL at all
 * (CLAUDE.md: a test that calls a `@Scheduled` method directly cannot see the HR000068 bug).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestProfile(TransactionPartitionSchedulerIT.FastPartitionCronProfile::class)
class TransactionPartitionSchedulerIT {

    class FastPartitionCronProfile : QuarkusTestProfile {
        // Literals only: a QuarkusTestProfile loads in a different classloader from the test.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.transaction.partition.every" to "1s",
            "openbank.transaction.partition.delayed" to "1s",
        )
    }

    @Inject
    lateinit var registry: MeterRegistry

    private fun successRecorded(): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "transaction-partition-maintenance")
        .gauge()
        ?.value()

    @Test
    fun `the real scheduler tick completes a maintenance pass and records liveness`() {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted {
            assertThat(successRecorded())
                .describedAs("the scheduled maintainer never recorded a successful pass")
                .isEqualTo(1.0)
        }
    }
}
