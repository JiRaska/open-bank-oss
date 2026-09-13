// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.application.port.out.GitHubMetricsPort
import com.openbank.devops.application.port.out.PrometheusQueryPort
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Signal collection (ADR-0119). The class exposes `runOnVertxContext` as `protected open` precisely
 * so a unit test can substitute the Vert.x bridge — the collection logic itself is plain suspend
 * code and needs no container.
 *
 * What is actually asserted here is the ABSENT-vs-ZERO distinction, which is the whole reason the
 * detectors can be inert: a null from a port must produce NO key (detector inert), while a null from
 * Prometheus for a gauge-like series must produce an explicit 0.0 (detector sees "nothing firing").
 * Collapsing the two would either silence a real finding or invent one.
 */
class CollectSignalsActivityImplTest {

    private val prometheus = mockk<PrometheusQueryPort>()
    private val github = mockk<GitHubMetricsPort>()

    /** Runs the collection body inline instead of on a Vert.x duplicated context. */
    private class TestCollect(p: PrometheusQueryPort, g: GitHubMetricsPort) : CollectSignalsActivityImpl(p, g) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private val collect = TestCollect(prometheus, github)

    @Test
    fun `an unavailable CI failure rate yields NO key, leaving the D1 detector inert`() {
        coEvery { github.ciFailureRate() } returns null

        assertThat(collect.collectCiPipelineSignals()).doesNotContainKey("ci_failure_rate").isEmpty()
    }

    @Test
    fun `a zero CI failure rate is still reported as a real signal`() {
        // 0.0 is a measurement ("nothing failed"), not an absence — it must reach the detector.
        coEvery { github.ciFailureRate() } returns 0.0

        assertThat(collect.collectCiPipelineSignals()).containsEntry("ci_failure_rate", 0.0)
    }

    @Test
    fun `open fleet-health issues are widened from Int to Double under the SSDLC key`() {
        coEvery { github.openFleetHealthIssues() } returns 7

        assertThat(collect.collectSsdlcSignals()).hasSize(1).containsEntry("open_fleet_health_issues", 7.0)
    }

    @Test
    fun `an unavailable issue count yields no SSDLC key`() {
        coEvery { github.openFleetHealthIssues() } returns null

        assertThat(collect.collectSsdlcSignals()).isEmpty()
    }

    @Test
    fun `the DORA CFR proxy falls back to 0 rather than dropping the key`() {
        coEvery { prometheus.queryInstant(any()) } returns null

        assertThat(collect.collectDoraSignals()).containsEntry("change_failure_rate_proxy", 0.0)
    }

    @Test
    fun `the DORA CFR proxy passes the measured ratio through`() {
        coEvery { prometheus.queryInstant(match { it.contains("http_server_requests_seconds_count") }) } returns 0.12

        assertThat(collect.collectDoraSignals()).containsEntry("change_failure_rate_proxy", 0.12)
    }

    @Test
    fun `runner-capacity collection reports assigned and running separately`() {
        coEvery { prometheus.queryInstant(match { it.contains("assigned_runners") }) } returns 4.0
        coEvery { prometheus.queryInstant(match { it.contains("running_runners") }) } returns 0.0

        val signals = collect.collectRunnerCapacitySignals()

        // Both keys must be present and NOT transposed — the stranded-pool detector reads
        // assigned>=1 && running<1, so swapping them inverts the 2026-06-27 incident verdict.
        assertThat(signals).containsEntry("arc_assigned_runners", 4.0).containsEntry("arc_running_runners", 0.0)
    }

    @Test
    fun `an unavailable ARC series degrades to zero on both runner keys`() {
        coEvery { prometheus.queryInstant(any()) } returns null

        assertThat(collect.collectRunnerCapacitySignals())
            .containsEntry("arc_assigned_runners", 0.0)
            .containsEntry("arc_running_runners", 0.0)
    }

    @Test
    fun `deploy-health collection reports the firing rollout alert count`() {
        coEvery { prometheus.queryInstant(match { it.contains("ALERTS") }) } returns 2.0

        assertThat(collect.collectDeployHealthSignals()).containsEntry("rollout_alerts_firing", 2.0)
    }

    @Test
    fun `incident-recurrence collection reports the max recurrence`() {
        coEvery { prometheus.queryInstant(match { it.contains("severity=\"critical\"") }) } returns 5.0

        assertThat(collect.collectIncidentRecurrenceSignals()).containsEntry("max_critical_alert_recurrence", 5.0)
    }
}
