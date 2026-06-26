// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import com.openbank.statement.application.port.out.StatementOutboxRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the statement outbox backlog as `openbank.outbox.backlog{service="statement"}`
 * (ADR-0077 / ADR-0079). A rising backlog means statement events are stuck on their way to Kafka.
 * All the logic lives in [AbstractOutboxBacklogGauge] (ADR-0049 consolidation); this bean only binds
 * the statement repository + the CDI/scheduler annotations (which, per ADR-0013, must sit on the
 * concrete bean).
 */
@Startup
@ApplicationScoped
class StatementOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: StatementOutboxRepository

    @Inject
    constructor(outboxRepository: StatementOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    // Required by Quarkus CDI for proxy subclass generation — never called at runtime
    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "statement"

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
