// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.lending.infrastructure.outbox

import com.openbank.lending.infrastructure.persistence.repository.LendingOutboxRepositoryImpl
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the lending outbox **backlog** (PENDING + FAILED rows) as the `openbank.outbox.backlog`
 * gauge tagged `service="lending"` (ADR-0077 / ADR-0079). A rising backlog means lending domain
 * events are stuck on their way to Kafka.
 *
 * Delegates cache + registration to [AbstractOutboxBacklogGauge] (ADR-0049 consolidation).
 */
@Startup
@ApplicationScoped
class LendingOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: LendingOutboxRepositoryImpl

    @Inject
    constructor(outboxRepository: LendingOutboxRepositoryImpl, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "lending"

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
