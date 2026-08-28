// SPDX-License-Identifier: Apache-2.0
package com.openbank.referral.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped

/**
 * Publishes the referral outbox backlog (PENDING + FAILED rows) as
 * `openbank.outbox.backlog{service="referral"}`. This is the fleet alerting signal for reward
 * lifecycle events that cannot reach Kafka.
 */
@Startup
@ApplicationScoped
class ReferralOutboxBacklogGauge(private val outboxRepository: ReferralOutboxRepository, metrics: DomainMetrics) :
    AbstractOutboxBacklogGauge(metrics) {
    override val service: String = "referral"

    override suspend fun currentBacklog(): Long = outboxRepository.countProcessable()

    @PostConstruct
    fun register() = registerBacklogGauge()

    @Scheduled(
        every = "10s",
        delayed = "10s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "referral-outbox-backlog-gauge",
    )
    suspend fun refresh() = refreshBacklog()
}
