// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the sanctions outbox **backlog** (PENDING + FAILED rows) as the
 * `openbank.outbox.backlog` gauge tagged `service="sanctions"` (ADR-0077 / ADR-0079). A rising
 * backlog means sanctions screening events are stuck on their way to Kafka.
 *
 * sanctions is the one service backed by the libs [SanctionsOutboxRepository] (= libs
 * `OutboxRepository`); its [SanctionsOutboxRepository.countProcessable] overrides the libs default
 * with an efficient `SELECT count(*)`. As with the other services, a scheduled `suspend` tick
 * refreshes a cached value via [AbstractOutboxBacklogGauge] and the Micrometer gauge supplier reads
 * that cache cheaply and lock-free on the scrape thread.
 */
@Startup
@ApplicationScoped
class SanctionsOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: SanctionsOutboxRepository

    @Inject
    constructor(outboxRepository: SanctionsOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "sanctions"
    override suspend fun currentBacklog(): Long = outboxRepository.countProcessable()

    @PostConstruct
    fun register() = registerBacklogGauge()

    @Scheduled(every = "10s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() = refreshBacklog()
}
