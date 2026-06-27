// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.outbox

import com.openbank.domestic.application.port.out.DomesticPaymentOutboxRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the domestic-payment outbox **backlog** (PENDING + FAILED rows) as the
 * `openbank.outbox.backlog` gauge tagged `service="domestic"` (ADR-0077 / ADR-0079). A rising backlog
 * means domestic-payment domain events are stuck on their way to Kafka.
 *
 * Delegates cache + registration to [AbstractOutboxBacklogGauge] (ADR-0049 consolidation).
 */
@Startup
@ApplicationScoped
class DomesticPaymentOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: DomesticPaymentOutboxRepository

    @Inject
    constructor(outboxRepository: DomesticPaymentOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "domestic"

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
