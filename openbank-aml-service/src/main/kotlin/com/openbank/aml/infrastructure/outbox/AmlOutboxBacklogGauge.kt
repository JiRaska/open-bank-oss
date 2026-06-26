// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.aml.infrastructure.outbox

import com.openbank.aml.infrastructure.persistence.repository.AmlOutboxRepositoryImpl
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the AML outbox **backlog** (PENDING + FAILED rows) as the `openbank.outbox.backlog`
 * gauge tagged `service="aml"` (ADR-0077 / ADR-0079). A rising backlog means AML screening
 * decisions are stuck on their way to Kafka — a key operational signal of the transactional outbox.
 */
@Startup
@ApplicationScoped
class AmlOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: AmlOutboxRepositoryImpl

    @Inject
    constructor(outboxRepository: AmlOutboxRepositoryImpl, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "aml"

    override suspend fun currentBacklog(): Long = outboxRepository.countProcessableUni().awaitSuspending()

    @PostConstruct
    fun register() = registerBacklogGauge()

    @Scheduled(
        every = "10s",
        delayed = "10s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() = refreshBacklog()
}
