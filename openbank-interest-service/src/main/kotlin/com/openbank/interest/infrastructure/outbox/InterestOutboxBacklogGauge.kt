// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.interest.infrastructure.outbox

import com.openbank.interest.infrastructure.persistence.repository.InterestOutboxRepositoryImpl
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the interest outbox **backlog** (PENDING + FAILED rows) as the `openbank.outbox.backlog`
 * gauge tagged `service="interest"` (ADR-0077 / ADR-0079). A rising backlog means interest events
 * are stuck on their way to Kafka. Terminal DEAD rows (ADR-0050 N5) are excluded — they are parked,
 * not backlogged.
 *
 * Delegates cache management and gauge registration to [AbstractOutboxBacklogGauge] (ADR-0049).
 */
@Startup
@ApplicationScoped
class InterestOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: InterestOutboxRepositoryImpl

    @Inject
    constructor(outboxRepository: InterestOutboxRepositoryImpl, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "interest"

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
