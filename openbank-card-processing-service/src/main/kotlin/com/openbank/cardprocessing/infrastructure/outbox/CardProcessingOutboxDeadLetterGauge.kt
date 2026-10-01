// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.cardprocessing.infrastructure.outbox

import com.openbank.cardprocessing.application.port.out.CardProcessingOutboxRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDeadLetterGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration

/**
 * Publishes the dead-letter count as `openbank.outbox.dead_lettered` tagged
 * `service="card-processing"` (#4005).
 *
 * A separate series from the backlog because `countProcessable` excludes DEAD by design: without
 * this, a pile of permanently-failed card events sits behind a backlog gauge reading a healthy zero
 * — which is exactly what card-issuance did for its entire life before #4163.
 *
 * Carries a real ADR-0237 heartbeat: the alert on this gauge is `> 0`, so a dead scheduler tick pins
 * it at the boot zero and the alert simply never fires. The backlog gauge's own freshness argument
 * does not transfer.
 */
@Startup
@ApplicationScoped
class CardProcessingOutboxDeadLetterGauge : AbstractOutboxDeadLetterGauge {
    private lateinit var outboxRepository: CardProcessingOutboxRepository
    private var domainMetrics: DomainMetrics? = null

    @Inject
    constructor(outboxRepository: CardProcessingOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
        this.domainMetrics = metrics
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "card-processing"

    override suspend fun currentDeadLettered(): Long = outboxRepository.countDead()

    @PostConstruct
    fun register() {
        registerDeadLetterGauge()
        domainMetrics?.let { bindLiveness(it.registerWorkflowLiveness(WORKFLOW_NAME, REFRESH_INTERVAL)) }
    }

    @Scheduled(every = "60s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() = refreshDeadLettered()

    private companion object {
        /** Must stay equal to the `every =` above — it is published as the ADR-0237 expected interval. */
        private val REFRESH_INTERVAL: Duration = Duration.ofSeconds(60)
        private const val WORKFLOW_NAME = "card-processing-outbox-dead-letter-gauge"
    }
}
