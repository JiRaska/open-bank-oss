// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.settlement.application.port.out.SettlementOutboxRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** Includes DEAD and stale DISPATCHING rows, so exhausting retries cannot clear the alert. */
@Startup
@ApplicationScoped
class SettlementAuditBacklogGauge(
    private val repository: SettlementOutboxRepository,
    registry: MeterRegistry,
    private val clock: Clock,
    metrics: DomainMetrics,
) {
    private val age = AtomicLong()
    private val liveness = metrics.registerWorkflowLiveness(
        "settlement-audit-backlog",
        Duration.ofSeconds(REFRESH_SECONDS),
    )

    init {
        Gauge.builder(METRIC, age) { it.get().toDouble() }.tag("service", "settlement")
            .strongReference(true).register(registry)
    }

    @Scheduled(every = "30s", delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() {
        val oldest = repository.oldestUnsentAt()
        age.set(oldest?.let { maxOf(0, Duration.between(it, clock.instant()).seconds) } ?: 0)
        liveness.recordSuccess()
    }

    companion object {
        private const val REFRESH_SECONDS = 30L
        const val METRIC = "openbank.settlement.audit.unsent.oldest.age.seconds"
    }
}
