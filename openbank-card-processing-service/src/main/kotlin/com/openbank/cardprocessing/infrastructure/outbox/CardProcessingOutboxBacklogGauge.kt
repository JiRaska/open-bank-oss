// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.cardprocessing.infrastructure.outbox

import com.openbank.cardprocessing.application.port.out.CardProcessingOutboxRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the card-processing outbox backlog (PENDING + FAILED) as `openbank.outbox.backlog`
 * tagged `service="card-processing"` (ADR-0077 / ADR-0079).
 *
 * `@Startup` is load-bearing: `@ApplicationScoped` is lazy, and a gauge that was never registered
 * reads on a dashboard exactly like a gauge that is zero.
 */
@Startup
@ApplicationScoped
class CardProcessingOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: CardProcessingOutboxRepository

    @Inject
    constructor(outboxRepository: CardProcessingOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "card-processing"

    override suspend fun currentBacklog(): Long = outboxRepository.countProcessable()

    @PostConstruct
    fun register() = registerBacklogGauge()

    @Scheduled(every = "10s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() = refreshBacklog()
}
