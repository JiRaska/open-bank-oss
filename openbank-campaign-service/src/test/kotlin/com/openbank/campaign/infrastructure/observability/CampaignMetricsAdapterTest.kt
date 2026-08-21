// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.observability

import com.openbank.campaign.application.port.out.EnrolmentAttempt
import com.openbank.campaign.application.port.out.SendHandoffOutcome
import com.openbank.campaign.application.port.out.StepResolution
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.EnrolmentState
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Asserts the adapter against a **real** [PrometheusMeterRegistry] and, for the alert-bearing
 * series, against the rendered scrape text — not against a mock and not against Micrometer's
 * in-memory meter names. The alert expressions in
 * `openbank-infra/gitops/components/campaign/prometheus-rules.yaml` are written over the *scraped*
 * names (`openbank_campaign_sends_total`), and only the rendered output can show that Micrometer's
 * dot-to-underscore mapping plus the `_total` suffix produce exactly those.
 *
 * The load-bearing case is [`every alert-bearing series exists at zero before any traffic`]:
 * Micrometer creates a counter on first increment, so a lazily-registered
 * `openbank_campaign_sends_total{outcome="handed_off"}` is **absent** — not zero — on a service
 * that has handed nothing off, and `increase(...[6h]) == 0` then matches nothing at all. That is
 * precisely the state the alert exists to catch, so a lazy counter would make the alert silent in
 * the only case it is for. Deleting the `bindTo` body must turn that test red.
 */
class CampaignMetricsAdapterTest {

    private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val adapter = CampaignMetricsAdapter().apply { bindTo(registry) }

    private fun counter(name: String, vararg tags: Pair<String, String>): Double? = registry.find(name)
        .let { search -> tags.fold(search) { s, (k, v) -> s.tag(k, v) } }
        .counter()
        ?.count()

    @Test
    fun `every alert-bearing series exists at zero before any traffic`() {
        Channel.entries.forEach { channel ->
            SendHandoffOutcome.entries.forEach { outcome ->
                assertThat(
                    counter(
                        CampaignMetricsAdapter.SENDS_METRIC,
                        "channel" to channel.name.lowercase(),
                        "outcome" to outcome.name.lowercase(),
                    ),
                ).describedAs("send %s/%s must be registered eagerly", channel, outcome).isEqualTo(0.0)
            }
        }
        StepResolution.entries.forEach { resolution ->
            assertThat(counter(CampaignMetricsAdapter.STEP_OUTCOMES_METRIC, "outcome" to resolution.name.lowercase()))
                .describedAs("step resolution %s must be registered eagerly", resolution)
                .isEqualTo(0.0)
        }
        EnrolmentAttempt.entries.forEach { attempt ->
            assertThat(counter(CampaignMetricsAdapter.ENROLMENTS_METRIC, "outcome" to attempt.name.lowercase()))
                .describedAs("enrolment outcome %s must be registered eagerly", attempt)
                .isEqualTo(0.0)
        }
        CampaignMetricsAdapter.TERMINAL_STATES.forEach { state ->
            assertThat(
                counter(CampaignMetricsAdapter.ENROLMENT_TERMINAL_METRIC, "state" to state.name.lowercase()),
            ).describedAs("terminal state %s must be registered eagerly", state).isEqualTo(0.0)
        }
    }

    @Test
    fun `the rendered scrape carries the exact strings the alert expressions match on`() {
        // Rendered scrape output, i.e. what Prometheus would actually store and the alert query.
        // A rule referencing a name that renders differently matches nothing while looking correct.
        val scrape = registry.scrape()

        assertThat(scrape)
            .contains("openbank_campaign_sends_total")
            .contains("openbank_campaign_step_outcomes_total")
            .contains("openbank_campaign_enrolments_total")
            .contains("openbank_campaign_enrolment_terminal_total")
            .contains("openbank_campaign_enrol_duration_seconds")
        // Every tag VALUE the campaign alerts filter on, as rendered.
        assertThat(scrape)
            .contains("""openbank_campaign_sends_total{channel="email",outcome="handed_off",service="campaign"} 0.0""")
            .contains("""openbank_campaign_sends_total{channel="email",outcome="dry_run",service="campaign"} 0.0""")
            .contains("""outcome="started"""")
            .contains("""outcome="failed"""")
            .contains("""outcome="suppressed_consent"""")
            .contains("""state="completed"""")
    }

    @Test
    fun `a dry run is counted on its own outcome and never as a hand-off`() {
        adapter.sendAttempted(Channel.EMAIL, SendHandoffOutcome.DRY_RUN)

        assertThat(sends("email", "dry_run")).isEqualTo(1.0)
        // The whole reason DRY_RUN is a separate enum value rather than a flag on a shared success:
        // openbank.campaign.dry-run defaults to true, so an environment that never sets it false
        // emits nothing at all while every layer above reports SENT.
        assertThat(sends("email", "handed_off")).isEqualTo(0.0)
        assertThat(sends("email", "failed")).isEqualTo(0.0)
    }

    @Test
    fun `each recording moves exactly the series it names`() {
        adapter.sendAttempted(Channel.PUSH, SendHandoffOutcome.HANDED_OFF)
        adapter.stepResolved(StepResolution.SUPPRESSED_QUIET_HOURS)
        adapter.enrolmentRecorded(EnrolmentAttempt.HOLDOUT)
        adapter.enrolmentTerminal(EnrolmentState.COMPLETED_GOAL_REACHED)
        adapter.enrolmentBatchCompleted(Duration.ofSeconds(3))

        assertThat(sends("push", "handed_off")).isEqualTo(1.0)
        assertThat(sends("email", "handed_off")).isEqualTo(0.0)
        assertThat(counter(CampaignMetricsAdapter.STEP_OUTCOMES_METRIC, "outcome" to "suppressed_quiet_hours"))
            .isEqualTo(1.0)
        assertThat(counter(CampaignMetricsAdapter.STEP_OUTCOMES_METRIC, "outcome" to "suppressed_consent"))
            .isEqualTo(0.0)
        assertThat(counter(CampaignMetricsAdapter.ENROLMENTS_METRIC, "outcome" to "holdout")).isEqualTo(1.0)
        assertThat(counter(CampaignMetricsAdapter.ENROLMENTS_METRIC, "outcome" to "started")).isEqualTo(0.0)
        assertThat(
            counter(CampaignMetricsAdapter.ENROLMENT_TERMINAL_METRIC, "state" to "completed_goal_reached"),
        ).isEqualTo(1.0)
        assertThat(counter(CampaignMetricsAdapter.ENROLMENT_TERMINAL_METRIC, "state" to "completed")).isEqualTo(0.0)

        val timer = registry.find(CampaignMetricsAdapter.ENROL_DURATION_METRIC).timer()
        assertThat(timer!!.count()).isEqualTo(1L)
        // Reads back the value that was handed in, so the timer cannot be recording a constant.
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(3.0)
    }

    @Test
    fun `an adapter that was never bound records nothing rather than throwing`() {
        // A missing MeterRegistry must leave the adapter inert. It sits on the journey path, and an
        // NPE there would turn "no metrics" into "no campaigns".
        val unbound = CampaignMetricsAdapter()

        unbound.sendAttempted(Channel.EMAIL, SendHandoffOutcome.HANDED_OFF)
        unbound.stepResolved(StepResolution.GOAL_REACHED)
        unbound.enrolmentRecorded(EnrolmentAttempt.STARTED)
        unbound.enrolmentTerminal(EnrolmentState.COMPLETED)
        unbound.enrolmentBatchCompleted(Duration.ofSeconds(1))

        assertThat(sends("email", "handed_off")).isEqualTo(0.0)
    }

    private fun sends(channel: String, outcome: String): Double? =
        counter(CampaignMetricsAdapter.SENDS_METRIC, "channel" to channel, "outcome" to outcome)
}
