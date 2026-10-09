// SPDX-License-Identifier: Apache-2.0
package com.openbank.tax.integration

import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.testing.containers.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** Drives the real Quarkus cron through a reactive PostgreSQL read, not a direct method call. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_scheduler_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@TestProfile(StatutoryReturnDeadlineSchedulerIT.FastDeadlineProfile::class)
class StatutoryReturnDeadlineSchedulerIT {
    class FastDeadlineProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.statutory-returns.deadline-check-every" to "2s",
            "openbank.statutory-returns.reporting-start" to "2099-01-01",
            "openbank.statutory-returns.fund-ids" to "test-fund",
        )
    }

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    fun `cron completes a real database query and records workflow success`() {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        var recorded = successRecorded()
        while (recorded != 1.0 && System.nanoTime() < deadline) {
            Thread.sleep(250)
            recorded = successRecorded()
        }
        assertThat(recorded)
            .describedAs("a real cron tick must finish its reactive repository query and record success")
            .isEqualTo(1.0)
    }

    private fun successRecorded(): Double? = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "statutory-return-deadline-check")
        .gauge()?.value()
}

class StatutoryReturnMessagingTestResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> =
        InMemoryConnector.switchIncomingChannelsToInMemory("withholding-remitted-in")

    override fun stop() = InMemoryConnector.clear()
}
