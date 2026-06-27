// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.application.port.out.GitHubMetricsPort
import com.openbank.devops.application.port.out.PrometheusQueryPort
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger

/**
 * Harvests SSDLC/DORA signals from the in-cluster observability stack (ADR-0119).
 *
 * The runner-capacity and deploy-health detectors run on live Prometheus series that
 * already exist (ARC controller metrics, the Argo Rollouts alert rules, the 5xx CFR
 * proxy and the ALERTS series). The CI-pipeline-health detector is wired to a GitHub
 * Actions exporter series that is the documented follow-up — it returns 0.0 (no signal)
 * until that exporter lands, so the detector is inert rather than noisy.
 */
@ApplicationScoped
open class CollectSignalsActivityImpl(
    private val prometheusQuery: PrometheusQueryPort,
    private val githubMetrics: GitHubMetricsPort,
) : CollectSignalsActivity {

    private val log = Logger.getLogger(CollectSignalsActivityImpl::class.java)

    override fun collectCiPipelineSignals(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting CI pipeline health signals (GitHub Actions)")
        // Read the failure rate straight from the GitHub Actions API (no exporter needed). null means
        // the token isn't seeded or the API failed -> no signal key -> the D1 detector stays inert.
        val rate = githubMetrics.ciFailureRate()
        if (rate != null) mapOf("ci_failure_rate" to rate) else emptyMap()
    }

    override fun collectSsdlcSignals(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting SSDLC hygiene signals (open fleet-health issues)")
        // Open `fleet-health` issues = accumulated CI/SSDLC drift the nightly build/lint jobs file.
        val openIssues = githubMetrics.openFleetHealthIssues()
        if (openIssues != null) mapOf("open_fleet_health_issues" to openIssues.toDouble()) else emptyMap()
    }

    override fun collectDoraSignals(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting DORA signals (CFR proxy)")
        // Change Failure Rate proxy: fleet-wide 5xx ratio over the last hour (same proxy the
        // admin-ui DORA route uses; ADR-0061 phase 1).
        val errorRate = prometheusQuery.queryInstant(
            "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[1h])) / " +
                "clamp_min(sum(rate(http_server_requests_seconds_count[1h])), 0.001)",
        ) ?: 0.0
        mapOf("change_failure_rate_proxy" to errorRate)
    }

    override fun collectRunnerCapacitySignals(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting runner-capacity signals")
        // ARC controller metrics. assigned = jobs wanting a runner; running = live runner pods.
        // ratio -> 1 means the queue is saturated; running == 0 with assigned > 0 means a pool
        // has zero online runners and jobs are stranded (the 2026-06-27 openbank-batch incident).
        val assigned = prometheusQuery.queryInstant(
            "sum(actions_runner_controller_ephemeral_runner_sets_assigned_runners)",
        ) ?: 0.0
        val running = prometheusQuery.queryInstant(
            "sum(actions_runner_controller_ephemeral_runner_sets_running_runners)",
        ) ?: 0.0
        mapOf(
            "arc_assigned_runners" to assigned,
            "arc_running_runners" to running,
        )
    }

    override fun collectDeployHealthSignals(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting deploy-health signals")
        // Argo Rollouts canary failure alerts already exist (prometheus-rules-tier1.yaml).
        val rolloutAlerts = prometheusQuery.queryInstant(
            "count(ALERTS{alertname=~\"Rollout.*\",alertstate=\"firing\"})",
        ) ?: 0.0
        mapOf("rollout_alerts_firing" to rolloutAlerts)
    }

    override fun collectIncidentRecurrenceSignals(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting incident-recurrence signals")
        // Max recurrence count of any single firing critical alert — a proxy for "the same thing
        // keeps breaking", which is the trigger for the learning-loop remediation.
        val maxRecurrence = prometheusQuery.queryInstant(
            "max(count by (alertname) (ALERTS{alertstate=\"firing\",severity=\"critical\"}))",
        ) ?: 0.0
        mapOf("max_critical_alert_recurrence" to maxRecurrence)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
