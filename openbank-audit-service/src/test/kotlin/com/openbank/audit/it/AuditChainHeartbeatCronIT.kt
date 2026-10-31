// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.it

import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Lets the REAL cron drive the chain verification and asserts the workflow heartbeat advances.
 * Not a direct call of `verify()`: that supplies the Vert.x context the scheduler does not, so it
 * passes against a scheduler that never runs. The cron below is a literal on purpose, since a
 * `QuarkusTestProfile` loads in a different classloader from the test class.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(AuditChainHeartbeatCronIT.EverySecondProfile::class)
class AuditChainHeartbeatCronIT {

    class EverySecondProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.audit.chain-verify.cron" to "*/1 * * * * ?",
            "quarkus.scheduler.enabled" to "true",
        )
    }

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    fun `the real cron advances the audit-chain-verify heartbeat`() {
        val deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS))
        fun recorded() = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
            .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "audit-chain-verify").gauge()?.value()
        while (Instant.now().isBefore(deadline) && recorded() != 1.0) Thread.sleep(POLL_MILLIS)

        assertThat(recorded())
            .describedAs("the scheduled chain verification never recorded a success within $TIMEOUT_SECONDS s")
            .isEqualTo(1.0)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 40L
        const val POLL_MILLIS = 250L
    }
}
