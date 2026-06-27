// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.standingorder.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import com.openbank.standingorder.application.port.out.StandingOrderOutboxRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the standing-order outbox **backlog** (PENDING + FAILED rows) as the
 * `openbank.outbox.backlog` gauge tagged `service="standing-order"` (ADR-0077 / ADR-0079).
 *
 * A scheduled `suspend` tick refreshes the cached backlog count via [AbstractOutboxBacklogGauge]
 * and the Micrometer gauge supplier reads that cache cheaply and lock-free on the scrape thread.
 */
@Startup
@ApplicationScoped
class StandingOrderOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: StandingOrderOutboxRepository

    @Inject
    constructor(outboxRepository: StandingOrderOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "standing-order"
    override suspend fun currentBacklog(): Long = outboxRepository.countProcessable()

    @PostConstruct
    fun register() = registerBacklogGauge()

    @Scheduled(every = "10s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() = refreshBacklog()
}
