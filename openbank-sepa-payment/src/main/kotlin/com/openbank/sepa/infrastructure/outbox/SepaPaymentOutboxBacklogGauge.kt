// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sepa.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge
import com.openbank.sepa.application.port.out.SepaPaymentOutboxRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped

/**
 * Publishes the sepa-payment outbox **backlog** (PENDING + FAILED rows) as the
 * `openbank.outbox.backlog` gauge tagged `service="sepa-payment"` (ADR-0077 / ADR-0079 / ADR-0049 D3).
 *
 * Extends [AbstractOutboxBacklogGauge] from openbank-libs. The base owns the gauge cache and
 * registration; this bean owns the CDI lifecycle and scheduling annotations — CDI interceptors
 * only fire on methods declared on the concrete bean.
 */
@Startup
@ApplicationScoped
class SepaPaymentOutboxBacklogGauge(
    private val outboxRepository: SepaPaymentOutboxRepository,
    metrics: DomainMetrics,
) : AbstractOutboxBacklogGauge(metrics) {

    override val service: String = "sepa-payment"

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
