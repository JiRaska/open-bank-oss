// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sepainstant.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import com.openbank.sepainstant.application.port.out.SctInstOutboxRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Publishes the SCT Inst outbox **backlog** (PENDING + FAILED rows) as the
 * `openbank.outbox.backlog{service="sepa-instant"}` gauge (ADR-0049 consolidation;
 * ADR-0077 / ADR-0079). A rising backlog means SEPA instant payment events are stuck
 * on their way to Kafka.
 *
 * Follows [AbstractOutboxBacklogGauge]: the scheduled tick refreshes the cached count
 * on the right reactive context; the Micrometer gauge supplier reads it lock-free on
 * the Prometheus scrape thread. CDI proxy constraint (ADR-0013): `@PostConstruct` and
 * `@Scheduled` MUST live on the concrete bean's methods, not on the abstract base.
 */
@Startup
@ApplicationScoped
class SctInstOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var outboxRepository: SctInstOutboxRepository

    @Inject
    constructor(outboxRepository: SctInstOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "sepa-instant"

    override suspend fun currentBacklog(): Long = outboxRepository.countProcessable()

    @PostConstruct
    fun register(): Unit = registerBacklogGauge()

    @Scheduled(
        every = "10s",
        delayed = "10s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh(): Unit = refreshBacklog()
}
