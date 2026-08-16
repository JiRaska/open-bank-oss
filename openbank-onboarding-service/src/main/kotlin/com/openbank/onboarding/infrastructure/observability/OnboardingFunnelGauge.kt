// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.onboarding.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.domain.model.FunnelStage
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the onboarding funnel stage distribution as Micrometer gauges (C8 prod-readiness
 * sweep / ADR-0077). The funnel is the cockpit board (ADR-0068 §4.2): each column is a stage;
 * a growing BLOCKED or KYC_UNDER_REVIEW count is an ops signal worth alerting on.
 *
 *  - `openbank_onboarding_funnel{service="onboarding",stage=<FunnelStage>}` — records in each stage.
 *
 * Micrometer samples gauge suppliers synchronously on the Prometheus scrape thread, but the
 * counts come from a suspend repository — so a scheduled tick refreshes cached [AtomicLong]s and
 * the suppliers read those caches cheaply and lock-free (same pattern as ComplaintDeadlineGauge).
 *
 * Service-local [MeterRegistry] (null-safe via [Instance]): onboarding-specific meters must not
 * force a fleet-wide rebuild (service-local metrics pattern, ADR-0085 §2). The `Instance`
 * constructor guard makes the bean safe in slim test slices where Prometheus is absent.
 */
@Startup
@ApplicationScoped
class OnboardingFunnelGauge(private val repository: OnboardingRepository, private val registry: MeterRegistry?) {
    @Inject
    constructor(repository: OnboardingRepository, registryInstance: Instance<MeterRegistry>) : this(
        repository,
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    private val stageCounts: Map<FunnelStage, AtomicLong> =
        FunnelStage.entries.associateWith { AtomicLong(0) }
    @Inject
    lateinit var domainMetrics: DomainMetrics
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        val r = registry ?: return
        for ((stage, counter) in stageCounts) {
            Gauge.builder(METRIC_FUNNEL, counter) { it.get().toDouble() }
                .tag("service", SERVICE)
                .tag("stage", stage.name)
                .description("Onboarding records in each funnel stage")
                .strongReference(true)
                .register(r)
        }
    }

    @Scheduled(
        every = "30s",
        delayed = "15s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() {
        for ((stage, counter) in stageCounts) {
            counter.set(repository.countByStage(stage))
        }
        liveness?.recordSuccess()
    }

    companion object {
        private const val SERVICE = "onboarding"
        private const val METRIC_FUNNEL = "openbank.onboarding.funnel"
        private const val WORKFLOW_NAME = "onboarding-funnel-gauge-refresh"
        private val EXPECTED_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
