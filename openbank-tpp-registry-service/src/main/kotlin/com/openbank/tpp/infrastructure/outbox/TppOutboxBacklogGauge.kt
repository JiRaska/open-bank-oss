// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.tpp.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import com.openbank.tpp.application.port.out.TppOutboxRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the TPP-registry outbox **backlog** (PENDING + FAILED rows) as the
 * `openbank.outbox.backlog` gauge tagged `service="tpp-registry"` (ADR-0077 / ADR-0079). A rising
 * backlog means TPP registry events are stuck on their way to Kafka.
 */
@Startup
@ApplicationScoped
class TppOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: TppOutboxRepository

    @Inject
    constructor(outboxRepository: TppOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "tpp-registry"

    override suspend fun currentBacklog(): Long = outboxRepository.countProcessable()

    @PostConstruct
    fun register() = registerBacklogGauge()

    @Scheduled(
        every = "10s",
        delayed = "10s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() = refreshBacklog()
}
