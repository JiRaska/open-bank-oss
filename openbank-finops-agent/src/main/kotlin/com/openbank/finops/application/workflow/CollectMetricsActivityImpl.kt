// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.application.port.out.PrometheusQueryPort
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger

@ApplicationScoped
open class CollectMetricsActivityImpl(private val prometheusQuery: PrometheusQueryPort) : CollectMetricsActivity {

    private val log = Logger.getLogger(CollectMetricsActivityImpl::class.java)

    override fun collectNatEgressMetrics(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting NAT egress metrics")
        val natEgress = prometheusQuery.queryInstant(
            "sum(increase(nat_gateway_bytes_out_total[1h]))",
        ) ?: 0.0
        mapOf("nat_egress_bytes_total" to natEgress)
    }

    override fun collectKarpenterMetrics(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting Karpenter node churn metrics")
        val terminations = prometheusQuery.queryInstant(
            "sum(increase(karpenter_nodes_terminated_total[1h]))",
        ) ?: 0.0
        val creations = prometheusQuery.queryInstant(
            "sum(increase(karpenter_nodes_created_total[1h]))",
        ) ?: 0.0
        mapOf(
            "node_terminations_per_hour" to terminations,
            "node_creations_per_hour" to creations,
        )
    }

    override fun collectEbsHealthMetrics(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting EBS health metrics")
        val multiAttach = prometheusQuery.queryInstant(
            "sum(increase(kube_persistentvolume_status_phase{phase=\"Failed\"}[1h]))",
        ) ?: 0.0
        mapOf("ebs_multi_attach_events" to multiAttach)
    }

    override fun collectCiRunnerMetrics(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting CI runner metrics")
        val cpuCores = prometheusQuery.queryInstant(
            "sum(container_spec_cpu_quota{container=\"runner\"}) / 100000",
        ) ?: 0.0
        val runnerCount = prometheusQuery.queryInstant(
            "count(kube_pod_info{pod=~\"arc-runner.*\"})",
        ) ?: 0.0
        mapOf(
            "arc_runner_cpu_cores" to cpuCores,
            "arc_runner_count" to runnerCount,
        )
    }

    override fun collectAiTokenMetrics(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting AI token budget metrics")
        val tokensUsed = prometheusQuery.queryInstant(
            "sum(increase(holmesgpt_tokens_total[24h]))",
        ) ?: 0.0
        mapOf("holmesgpt_tokens_used_today" to tokensUsed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
