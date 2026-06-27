// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import com.openbank.sdd.application.port.out.SddOutboxRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the SDD transactional outbox backlog (PENDING + FAILED rows) as the
 * `openbank.outbox.backlog{service="sdd"}` gauge (ADR-0077 / ADR-0079 C8 sweep).
 *
 * A rising backlog means SDD mandate lifecycle events are stuck on their way to Kafka
 * — an ops signal before customer-facing timeouts surface the problem.
 *
 * Follows [AbstractOutboxBacklogGauge]: the scheduled tick writes a cached [AtomicLong]
 * on the right reactive context; the Micrometer gauge supplier reads it lock-free on the
 * Prometheus scrape thread. CDI proxy constraint (ADR-0013): `@PostConstruct` and
 * `@Scheduled` MUST live on the concrete bean's methods, not on the abstract base.
 */
@Startup
@ApplicationScoped
class SddOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: SddOutboxRepository

    @Inject
    constructor(outboxRepository: SddOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "sdd"

    override suspend fun currentBacklog(): Long = outboxRepository.countProcessable()

    @PostConstruct
    fun register(): Unit = registerBacklogGauge()

    @Scheduled(
        every = "10s",
        delayed = "10s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh(): Unit = refreshBacklog()
}
