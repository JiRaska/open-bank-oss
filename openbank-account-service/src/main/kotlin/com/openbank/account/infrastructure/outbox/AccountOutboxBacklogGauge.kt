// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.account.infrastructure.outbox

import com.openbank.account.application.port.out.AccountOutboxRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped

/**
 * Publishes the account outbox **backlog** (PENDING + FAILED rows) as the `openbank.outbox.backlog`
 * gauge tagged `service="account"` (ADR-0077 / ADR-0079). A rising backlog means account lifecycle
 * events are stuck on their way to Kafka.
 *
 * Delegates caching, registration, and refresh logic to [AbstractOutboxBacklogGauge] (ADR-0049).
 */
@Startup
@ApplicationScoped
class AccountOutboxBacklogGauge(private val outboxRepository: AccountOutboxRepository, metrics: DomainMetrics) :
    AbstractOutboxBacklogGauge(
        metrics,
    ) {

    override val service: String = "account"

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
