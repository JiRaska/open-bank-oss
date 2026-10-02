// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import com.openbank.risk.infrastructure.persistence.PgRiskOutbox
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * `openbank.outbox.backlog{service="risk"}` (ADR-0077 / ADR-0079): limit events stuck on their way
 * to Kafka. `service` matches the tag [RiskOutboxDispatcher] derives from its own class name.
 */
@Startup
@ApplicationScoped
class RiskOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outbox: PgRiskOutbox

    @Inject
    constructor(outbox: PgRiskOutbox, metrics: DomainMetrics) : super(metrics) {
        this.outbox = outbox
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "risk"

    override suspend fun currentBacklog(): Long = outbox.countProcessable()

    @PostConstruct
    fun register() = registerBacklogGauge()

    @Scheduled(every = "10s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() = refreshBacklog()
}
