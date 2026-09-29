// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.referral.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDeadLetterGauge
import com.openbank.referral.infrastructure.persistence.repository.ReferralOutboxRepositoryImpl
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration

/**
 * Publishes the referral outbox **dead-letter** count as the `openbank.outbox.dead_lettered`
 * gauge tagged `service="referral"` — the retry/backlog telemetry half of #7190's acceptance
 * criteria. `openbank.outbox.backlog` (see [ReferralOutboxBacklogGauge]) deliberately excludes
 * DEAD rows, so a reward whose event exhausted every retry and was parked reads as an empty
 * backlog on that gauge alone; this one is the signal that would otherwise be missing.
 */
@Startup
@ApplicationScoped
class ReferralOutboxDeadLetterGauge : AbstractOutboxDeadLetterGauge {
    private lateinit var outboxRepository: ReferralOutboxRepositoryImpl
    private var domainMetrics: DomainMetrics? = null

    @Inject
    constructor(outboxRepository: ReferralOutboxRepositoryImpl, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
        this.domainMetrics = metrics
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "referral"

    override suspend fun currentDeadLettered(): Long = outboxRepository.countDead()

    @PostConstruct
    fun register() {
        registerDeadLetterGauge()
        domainMetrics?.let { bindLiveness(it.registerWorkflowLiveness(WORKFLOW_NAME, REFRESH_INTERVAL)) }
    }

    // suspend fun, not a plain one: a @Scheduled method carries no Vert.x context, so a
    // runBlocking around the reactive count would throw HR000068 and leave the gauge pinned at
    // its boot zero — the reading that looks healthiest.
    @Scheduled(
        every = "60s",
        delayed = "10s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() = refreshDeadLettered()

    private companion object {
        private val REFRESH_INTERVAL: Duration = Duration.ofSeconds(60)
        private const val WORKFLOW_NAME = "referral-outbox-dead-letter-gauge"
    }
}
