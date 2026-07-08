// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the billing outbox **backlog** (PENDING + FAILED rows) as the `openbank.outbox.backlog`
 * gauge tagged `service="billing"` (ADR-0077 / ADR-0079). A rising backlog means fee charges are
 * stuck on their way to the ledger — an operationally significant signal for a money-path service.
 * Terminal DEAD rows (ADR-0050 N5) are excluded — they are parked, not backlogged (surfaced instead
 * via the assessed fee's own FAILED posting_status).
 */
@Startup
@ApplicationScoped
class BillingOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: BillingOutboxRepositoryImpl

    @Inject
    constructor(outboxRepository: BillingOutboxRepositoryImpl, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "billing"

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
