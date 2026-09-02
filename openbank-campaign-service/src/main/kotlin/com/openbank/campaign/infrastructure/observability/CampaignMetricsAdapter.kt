// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.observability

import com.openbank.campaign.application.port.out.CampaignMetricsPort
import com.openbank.campaign.application.port.out.EnrolmentAttempt
import com.openbank.campaign.application.port.out.SendHandoffOutcome
import com.openbank.campaign.application.port.out.StepResolution
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.EnrolmentState
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Duration

/**
 * Micrometer adapter for [CampaignMetricsPort] (issue #5705 item 4). Emits, all tagged
 * `service="campaign"`:
 *
 *  - `openbank_campaign_sends_total{channel,outcome}` — delivery attempts, split
 *    `handed_off` / `dry_run` / `failed`. The `dry_run` split is the load-bearing one.
 *  - `openbank_campaign_step_outcomes_total{outcome}` — journey steps that resolved without
 *    attempting a delivery: suppressions, untaken branches, closed/paused campaigns.
 *  - `openbank_campaign_enrolments_total{outcome}` — per-party enrolment attempts,
 *    `started` / `holdout` / `failed`.
 *  - `openbank_campaign_enrolment_terminal_total{state}` — enrolments reaching a terminal state.
 *  - `openbank_campaign_enrol_duration_seconds` — one whole sweep, segment evaluation included.
 *
 * ### Why the counters are registered eagerly, and why that is the point
 *
 * Micrometer does not create a counter until its first increment, so
 * `increase(openbank_campaign_sends_total{outcome="handed_off"}[6h]) == 0` written against a
 * lazily-created counter matches **nothing at all** on a service that has never handed anything off
 * — which is exactly the state the alert exists to catch. Every series an alert reads is therefore
 * bound in [bindTo] at `@PostConstruct`, present at `0.0` from the first scrape. `@Startup` because
 * `@ApplicationScoped` is lazy: without it the bean, and so the meters, would not exist until the
 * first journey step ran.
 *
 * The eager label space is small and fully enumerable — three channels, and closed enums everywhere
 * else — so there is no cardinality argument against binding all of it up front.
 *
 * Service-local `MeterRegistry` via [Instance] exactly like libs `DomainMetrics` and the fleet's
 * other per-service adapters: journey shape is campaign-specific, so adding it to the shared libs
 * facade would force a fleet-wide rebuild for a one-service concern. Field injection of
 * `Instance<MeterRegistry>` rather than a nullable constructor parameter: a nullable parameter needs
 * a second `@Inject` constructor, and ArC registers no bean at all when it sees two plain ones.
 */
@Startup
@ApplicationScoped
class CampaignMetricsAdapter : CampaignMetricsPort {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private var registry: MeterRegistry? = null

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) bindTo(registryInstance.get())
    }

    /**
     * Bind the meters to [registry]. Called once at startup by [register]; exposed so a test can
     * bind a real Prometheus registry and assert the rendered series names and label sets, rather
     * than assuming how Micrometer maps them.
     */
    fun bindTo(registry: MeterRegistry) {
        this.registry = registry
        // Everything an alert expression reads is created here, before any traffic.
        Channel.entries.forEach { channel ->
            SendHandoffOutcome.entries.forEach { outcome -> sendCounter(registry, channel, outcome) }
        }
        StepResolution.entries.forEach { resolution -> stepOutcomeCounter(registry, resolution) }
        EnrolmentAttempt.entries.forEach { attempt -> enrolmentCounter(registry, attempt) }
        TERMINAL_STATES.forEach { state -> terminalCounter(registry, state) }
        enrolTimer(registry)
    }

    override fun sendAttempted(channel: Channel, outcome: SendHandoffOutcome) {
        registry?.let { r -> sendCounter(r, channel, outcome).increment() }
    }

    override fun stepResolved(outcome: StepResolution) {
        registry?.let { r -> stepOutcomeCounter(r, outcome).increment() }
    }

    override fun enrolmentRecorded(outcome: EnrolmentAttempt) {
        registry?.let { r -> enrolmentCounter(r, outcome).increment() }
    }

    override fun enrolmentBatchCompleted(duration: Duration) {
        registry?.let { r -> enrolTimer(r).record(duration.coerceAtLeast(Duration.ZERO)) }
    }

    override fun enrolmentTerminal(state: EnrolmentState) {
        registry?.let { r -> terminalCounter(r, state).increment() }
    }

    companion object {
        const val SERVICE = "campaign"

        const val SENDS_METRIC = "openbank.campaign.sends"
        const val STEP_OUTCOMES_METRIC = "openbank.campaign.step.outcomes"
        const val ENROLMENTS_METRIC = "openbank.campaign.enrolments"
        const val ENROLMENT_TERMINAL_METRIC = "openbank.campaign.enrolment.terminal"
        const val ENROL_DURATION_METRIC = "openbank.campaign.enrol.duration"

        /**
         * The states reachable from `markCompleted` / `markTerminated`. `HOLDOUT` and `ACTIVE` are
         * deliberately absent: the first is an enrolment-time assignment already counted by
         * `openbank_campaign_enrolments_total{outcome="holdout"}`, and the second is not terminal.
         */
        val TERMINAL_STATES: List<EnrolmentState> = listOf(
            EnrolmentState.COMPLETED,
            EnrolmentState.COMPLETED_GOAL_REACHED,
            EnrolmentState.TERMINATED_CONSENT_REVOKED,
            EnrolmentState.TERMINATED_CAMPAIGN_CLOSED,
            EnrolmentState.TERMINATED_SUPPRESSED,
            EnrolmentState.STOPPED_MAX_SENDS,
        )
    }
}

// The five meter builders below are top-level privates rather than members. detekt's
// TooManyFunctions fires AT its threshold of 11, and the port's five overrides plus `register` and
// `bindTo` already reach seven. Declared AFTER the class on purpose — a Kotlin annotation binds to
// the NEXT declaration, so a top-level function placed above an annotated class silently steals its
// annotation (the `@Path`/McpEndpoint 404 this repo has already shipped once).

private fun sendCounter(registry: MeterRegistry, channel: Channel, outcome: SendHandoffOutcome): Counter =
    Counter.builder(CampaignMetricsAdapter.SENDS_METRIC)
        .tag("service", CampaignMetricsAdapter.SERVICE)
        .tag("channel", channel.name.lowercase())
        .tag("outcome", outcome.name.lowercase())
        .description("Journey-step delivery attempts, split hand-off vs dry-run vs failed")
        .register(registry)

private fun stepOutcomeCounter(registry: MeterRegistry, outcome: StepResolution): Counter =
    Counter.builder(CampaignMetricsAdapter.STEP_OUTCOMES_METRIC)
        .tag("service", CampaignMetricsAdapter.SERVICE)
        .tag("outcome", outcome.name.lowercase())
        .description("Journey steps that resolved without attempting a delivery")
        .register(registry)

private fun enrolmentCounter(registry: MeterRegistry, outcome: EnrolmentAttempt): Counter =
    Counter.builder(CampaignMetricsAdapter.ENROLMENTS_METRIC)
        .tag("service", CampaignMetricsAdapter.SERVICE)
        .tag("outcome", outcome.name.lowercase())
        .description("Per-party enrolment attempts, from the scheduled sweep or a trigger event")
        .register(registry)

private fun terminalCounter(registry: MeterRegistry, state: EnrolmentState): Counter =
    Counter.builder(CampaignMetricsAdapter.ENROLMENT_TERMINAL_METRIC)
        .tag("service", CampaignMetricsAdapter.SERVICE)
        .tag("state", state.name.lowercase())
        .description("Enrolments that reached a terminal state")
        .register(registry)

private fun enrolTimer(registry: MeterRegistry): Timer = Timer.builder(CampaignMetricsAdapter.ENROL_DURATION_METRIC)
    .tag("service", CampaignMetricsAdapter.SERVICE)
    .publishPercentiles(P50_PCT, P95_PCT, P99_PCT)
    .publishPercentileHistogram()
    .description("Time to run one whole campaign enrolment sweep, segment evaluation included")
    .register(registry)

// The fleet-standard percentile set (libs DomainMetrics publishes the same three). Declared as
// constants because detekt MagicNumber fires on each literal passed to publishPercentiles — three
// violations per call site — and top-level because the builder that uses them is top-level.
private const val P50_PCT = 0.5
private const val P95_PCT = 0.95
private const val P99_PCT = 0.99
