// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.analytics.infrastructure.health

import com.openbank.analytics.application.IngestFreshness
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

/**
 * Readiness probe tying analytics freshness to the platform (ADR-0023, F8 / DORA monitoring).
 *
 * Reports DOWN when ingest lag exceeds the configured RPO threshold or the dead-letter count crosses
 * its alert threshold, so a stalled or badly-lagging sink is surfaced to the orchestrator and on-call
 * rather than silently drifting. Thresholds are config-driven so the agreed RPO can be tuned without a
 * code change. Before the first event the probe is UP (a freshly-started sink is not "stale").
 */
@Readiness
@ApplicationScoped
class IngestHealthCheck : HealthCheck {

    @Inject lateinit var freshness: IngestFreshness

    @ConfigProperty(name = "openbank.analytics.health.max-lag-seconds", defaultValue = "900")
    var maxLagSeconds: Long = 900

    @ConfigProperty(name = "openbank.analytics.health.max-dead-letters", defaultValue = "100")
    var maxDeadLetters: Long = 100

    override fun call(): HealthCheckResponse {
        val lag = freshness.currentLagSeconds()
        val dlq = freshness.deadLetters()
        val lagOk = lag < 0 || lag <= maxLagSeconds
        val dlqOk = dlq <= maxDeadLetters
        val builder = HealthCheckResponse.named("analytics-ingest-freshness")
            .withData("lagSeconds", lag)
            .withData("maxLagSeconds", maxLagSeconds)
            .withData("deadLetters", dlq)
            .withData("maxDeadLetters", maxDeadLetters)
            .withData("lastIngestEpochMs", freshness.lastIngestEpochMs())
        return if (lagOk && dlqOk) builder.up().build() else builder.down().build()
    }
}
