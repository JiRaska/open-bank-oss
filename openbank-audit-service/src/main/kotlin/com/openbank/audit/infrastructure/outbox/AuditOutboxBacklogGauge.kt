// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.infrastructure.outbox

import com.openbank.audit.application.port.out.AuditOutboxRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the audit outbox backlog as `openbank.outbox.backlog{service="audit"}`
 * (ADR-0077 / ADR-0079). A rising backlog means audit events are stuck on their way to Kafka.
 * All the logic lives in [AbstractOutboxBacklogGauge] (ADR-0049 consolidation); this bean only binds
 * the audit repository + the CDI/scheduler annotations (which, per ADR-0013, must sit on the
 * concrete bean).
 */
@Startup
@ApplicationScoped
class AuditOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: AuditOutboxRepository

    @Inject
    constructor(outboxRepository: AuditOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    // Required by Quarkus CDI for proxy subclass generation — never called at runtime
    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "audit"

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
