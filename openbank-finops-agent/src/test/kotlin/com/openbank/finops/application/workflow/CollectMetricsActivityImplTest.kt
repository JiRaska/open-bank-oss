// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.application.port.out.PrometheusQueryPort
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test

/**
 * The collect activity's only real logic is the `?: 0.0` fallback and the metric KEYS it emits.
 * Both matter: the keys are the contract the detectors read by name (a renamed key makes every
 * detector silently detect nothing), and the fallback decides whether an absent Prometheus series
 * reads as "no traffic" rather than aborting the sweep.
 */
class CollectMetricsActivityImplTest {

    private val prometheus = mockk<PrometheusQueryPort>()

    /** The production bridge needs a Vert.x context a plain unit test has none of. */
    private class SyncCollect(port: PrometheusQueryPort) : CollectMetricsActivityImpl(port) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private val collect = SyncCollect(prometheus)

    private fun stub(vararg values: Pair<String, Double?>) {
        coEvery { prometheus.queryInstant(any()) } answers { null }
        values.forEach { (fragment, value) ->
            coEvery { prometheus.queryInstant(match { it.contains(fragment) }) } returns value
        }
    }

    @Test
    fun `NAT egress is published under the key the D1 detector reads`() {
        stub("nat_gateway_bytes_out_total" to 1234.5)

        assertThat(collect.collectNatEgressMetrics()).containsExactly(entry("nat_egress_bytes_total", 1234.5))
    }

    @Test
    fun `an absent NAT series collapses to zero rather than to a missing key`() {
        stub("nat_gateway_bytes_out_total" to null)

        val metrics = collect.collectNatEgressMetrics()

        assertThat(metrics).containsKey("nat_egress_bytes_total")
        assertThat(metrics["nat_egress_bytes_total"]).isEqualTo(0.0)
    }

    @Test
    fun `karpenter collection keeps terminations and creations apart`() {
        stub(
            "karpenter_nodes_terminated_total" to 7.0,
            "karpenter_nodes_created_total" to 2.0,
        )

        assertThat(collect.collectKarpenterMetrics())
            .containsEntry("node_terminations_per_hour", 7.0)
            .containsEntry("node_creations_per_hour", 2.0)
    }

    @Test
    fun `one absent karpenter series does not zero the other`() {
        stub(
            "karpenter_nodes_terminated_total" to null,
            "karpenter_nodes_created_total" to 5.0,
        )

        assertThat(collect.collectKarpenterMetrics())
            .containsEntry("node_terminations_per_hour", 0.0)
            .containsEntry("node_creations_per_hour", 5.0)
    }

    @Test
    fun `EBS health counts only Failed persistent volumes`(): Unit = runBlocking {
        val queries = mutableListOf<String>()
        coEvery { prometheus.queryInstant(capture(queries)) } returns 3.0

        assertThat(collect.collectEbsHealthMetrics()).containsEntry("ebs_multi_attach_events", 3.0)
        assertThat(queries).singleElement().asString().contains("phase=\"Failed\"")
    }

    @Test
    fun `CI runner collection emits both the core count and the runner count`() {
        stub(
            "container_spec_cpu_quota" to 48.0,
            "kube_pod_info" to 12.0,
        )

        assertThat(collect.collectCiRunnerMetrics())
            .containsEntry("arc_runner_cpu_cores", 48.0)
            .containsEntry("arc_runner_count", 12.0)
    }

    @Test
    fun `AI token collection reports the 24h window under the D6 key`() {
        stub("holmesgpt_tokens_total" to 410_000.0)

        assertThat(collect.collectAiTokenMetrics())
            .containsEntry("holmesgpt_tokens_used_today", 410_000.0)
    }

    @Test
    fun `every collected map is consumable by its detector without renaming`() {
        stub(
            "nat_gateway_bytes_out_total" to 0.0,
            "karpenter_nodes_terminated_total" to 0.0,
            "karpenter_nodes_created_total" to 0.0,
            "kube_persistentvolume_status_phase" to 0.0,
            "container_spec_cpu_quota" to 0.0,
            "kube_pod_info" to 0.0,
            "holmesgpt_tokens_total" to 0.0,
        )

        val allKeys = collect.collectNatEgressMetrics().keys +
            collect.collectKarpenterMetrics().keys +
            collect.collectEbsHealthMetrics().keys +
            collect.collectCiRunnerMetrics().keys +
            collect.collectAiTokenMetrics().keys

        assertThat(allKeys).containsExactlyInAnyOrder(
            "nat_egress_bytes_total",
            "node_terminations_per_hour",
            "node_creations_per_hour",
            "ebs_multi_attach_events",
            "arc_runner_cpu_cores",
            "arc_runner_count",
            "holmesgpt_tokens_used_today",
        )
    }
}
