// SPDX-License-Identifier: Apache-2.0
package com.openbank.tax.integration

import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.testing.containers.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The real cron on a deployment that has not declared a reporting start: the workflow reports
 * itself unconfigured instead of looking like a dead scheduler. The configured sibling
 * ([StatutoryReturnDeadlineSchedulerIT]) is the negative case, where the flag stays 0.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_unconfigured_it")],
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@TestProfile(StatutoryReturnDeadlineUnconfiguredIT.UnconfiguredProfile::class)
class StatutoryReturnDeadlineUnconfiguredIT {
    class UnconfiguredProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.statutory-returns.deadline-check-every" to "1s",
        )
    }

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    fun `an unconfigured deployment reports unconfigured and records no success`() {
        // Several ticks at 1s: a skipped tick must still not record success.
        Thread.sleep(SETTLE_MILLIS)
        assertThat(unconfigured()).isEqualTo(1.0)
        assertThat(successRecorded()).isEqualTo(0.0)
    }

    private fun unconfigured(): Double? = registry.find("openbank_workflow_unconfigured")
        .tag("workflow", "statutory-return-deadline-check")
        .gauge()?.value()

    private fun successRecorded(): Double? = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "statutory-return-deadline-check")
        .gauge()?.value()

    private companion object {
        const val SETTLE_MILLIS = 4_000L
    }
}
