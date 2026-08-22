// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDeadLetterGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration

/**
 * Publishes the billing outbox **dead-letter** count as the `openbank.outbox.dead_lettered` gauge
 * tagged `service="billing"` (#4701, the sibling of #4005).
 *
 * Two `billing.fee.post-intent.v1` rows have sat in terminal DEAD since 2026-07-13, at the
 * `attempt_count = 10` ceiling, carrying
 * `BillingOutboxDispatcher#publishWithResilience circuit breaker is open` — the same latched-breaker
 * signature #4163 fixed. Throughout, `openbank_outbox_backlog{service="billing"}` read `0`, because
 * [BillingOutboxBacklogGauge] counts PENDING + FAILED and DEAD is excluded by design. A parked
 * money-path outbox is therefore indistinguishable from an idle healthy one on every signal that
 * existed, which is why this ran unnoticed for a month.
 *
 * ### Why the fee's own `posting_status` is not already this signal
 * [BillingOutboxRepositoryImpl.markFailed] does flip the originating `AssessedFee` to
 * `PostingStatus.FAILED` when a row goes DEAD, and both stranded fees are `FAILED` in the table
 * today. That makes the loss *recorded*, not *alertable*: nothing exports `posting_status` as a
 * metric and no PrometheusRule reads it, so it is visible only to someone already running the
 * query. The gauge is the part that speaks without being asked.
 *
 * Refreshed every 60s rather than the backlog gauge's 10s: dead-lettering is a rare, terminal
 * event and the alert on it carries a 15m `for:`, so a minute of staleness costs nothing.
 */
@Startup
@ApplicationScoped
class BillingOutboxDeadLetterGauge : AbstractOutboxDeadLetterGauge {
    private lateinit var outboxRepository: BillingOutboxRepositoryImpl
    private var domainMetrics: DomainMetrics? = null

    @Inject
    constructor(outboxRepository: BillingOutboxRepositoryImpl, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
        this.domainMetrics = metrics
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "billing"

    override suspend fun currentDeadLettered(): Long = outboxRepository.countDead()

    /**
     * ADR-0237: the outbox exemption in `check-scheduler-liveness.py` rests on
     * `openbank.outbox.backlog` being its own freshness signal, and that argument does not carry
     * here — the alert on this gauge is `> 0`, so a dead tick pins it at the boot ZERO and the
     * alert simply never fires. So it carries a real heartbeat.
     */
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
        /**
         * Must stay equal to the `every =` above — it is published as the ADR-0237
         * expected-interval, and a heartbeat whose declared interval disagrees with the real cron
         * is a staleness rule that either never fires or fires constantly.
         */
        private val REFRESH_INTERVAL: Duration = Duration.ofSeconds(60)
        private const val WORKFLOW_NAME = "billing-outbox-dead-letter-gauge"
    }
}
