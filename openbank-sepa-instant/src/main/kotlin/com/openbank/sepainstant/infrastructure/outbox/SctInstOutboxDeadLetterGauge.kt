// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDeadLetterGauge
import com.openbank.sepainstant.application.port.out.SctInstOutboxRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration

/** DEAD rows are excluded from dispatch/backlog and require an independently refreshed gauge. */
@Startup
@ApplicationScoped
class SctInstOutboxDeadLetterGauge : AbstractOutboxDeadLetterGauge {
    private lateinit var repository: SctInstOutboxRepository
    private var domainMetrics: DomainMetrics? = null

    @Inject
    constructor(repository: SctInstOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.repository = repository
        this.domainMetrics = metrics
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "sepa-instant"

    override suspend fun currentDeadLettered(): Long = repository.countDead()

    @PostConstruct
    fun register() {
        registerDeadLetterGauge()
        domainMetrics?.let { bindLiveness(it.registerWorkflowLiveness(WORKFLOW_NAME, REFRESH_INTERVAL)) }
    }

    @Scheduled(every = "60s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() = refreshDeadLettered()

    private companion object {
        private val REFRESH_INTERVAL: Duration = Duration.ofSeconds(60)
        private const val WORKFLOW_NAME = "sepa-instant-outbox-dead-letter-gauge"
    }
}
