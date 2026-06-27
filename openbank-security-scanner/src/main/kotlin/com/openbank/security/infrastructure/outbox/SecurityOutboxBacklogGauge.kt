// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.outbox

import com.openbank.security.infrastructure.persistence.repository.SecurityOutboxRepositoryImpl
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the security-scanner outbox **backlog** (PENDING + FAILED rows) as the
 * `openbank.outbox.backlog` gauge tagged `service="security-scanner"` (ADR-0077 / ADR-0079). A
 * rising backlog means security scan events are stuck on their way to Kafka.
 *
 * Delegates cache management and gauge registration to [AbstractOutboxBacklogGauge] (ADR-0049).
 */
@Startup
@ApplicationScoped
class SecurityOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: SecurityOutboxRepositoryImpl

    @Inject
    constructor(outboxRepository: SecurityOutboxRepositoryImpl, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "security-scanner"

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
