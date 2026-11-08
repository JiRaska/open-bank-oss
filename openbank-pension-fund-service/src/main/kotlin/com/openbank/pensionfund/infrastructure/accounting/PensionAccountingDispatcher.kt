// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.Timeout

@ApplicationScoped
class PensionAccountingDispatcher(
    private val repo: PensionAccountingOutbox,
    private val publisher: PensionFeePublisher,
    private val config: PensionAccountingConfig,
    metrics: DomainMetrics,
) : AbstractOutboxDispatcher(metrics) {
    private companion object {
        const val DISPATCH_TIMEOUT_MS = 30000L
        const val DELIVERY_TIMEOUT_MS = 15000L
    }

    override val service: String get() = "pension-fund-accounting"
    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.pension-fund.accounting.poll-interval:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "pension-accounting-delivery",
    )
    @Bulkhead(1)
    @Timeout(DISPATCH_TIMEOUT_MS)
    suspend fun dispatch() {
        if (config.deliveryEnabled()) dispatchScheduledBatch()
    }

    @Bulkhead(1)
    @Timeout(DELIVERY_TIMEOUT_MS)
    override suspend fun publishWithResilience(entry: OutboxEntry) = publisher.publish(entry)
}
