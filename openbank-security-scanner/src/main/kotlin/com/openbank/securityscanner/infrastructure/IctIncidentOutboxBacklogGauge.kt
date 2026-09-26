// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.securityscanner.infrastructure

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import com.openbank.security.application.port.out.IctIncidentOutboxRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@Startup
@ApplicationScoped
class IctIncidentOutboxBacklogGauge : AbstractOutboxBacklogGauge {
    private lateinit var repository: IctIncidentOutboxRepository

    @Inject
    constructor(repository: IctIncidentOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.repository = repository
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "security-scanner"
    override suspend fun currentBacklog(): Long = repository.countProcessable()

    @PostConstruct
    fun register() = registerBacklogGauge()

    @Scheduled(every = "10s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() = refreshBacklog()
}
