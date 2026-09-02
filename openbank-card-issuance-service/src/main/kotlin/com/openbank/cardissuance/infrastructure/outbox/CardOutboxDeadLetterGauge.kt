// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.cardissuance.infrastructure.outbox

import com.openbank.cardissuance.application.port.out.CardOutboxRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDeadLetterGauge
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration

/**
 * Publishes the card-issuance outbox **dead-letter** count as the
 * `openbank.outbox.dead_lettered` gauge tagged `service="card-issuance"` (#4005).
 *
 * This service is the reason the gauge exists: every one of its 24 outbox rows was parked in
 * terminal DEAD by a latched-open circuit breaker, so it had published zero events for its entire
 * life while `openbank_outbox_backlog{service="card-issuance"}` sat at a healthy-looking `0` —
 * DEAD is excluded from the backlog by design. The mechanism was fixed in #4163; this is the
 * signal that would have said so.
 *
 * Refreshed every 60s rather than the backlog gauge's 10s: dead-lettering is a rare, terminal
 * event and the alert on it is a 15m `for:`, so a minute of staleness costs nothing while a
 * `count(*)` over an unindexed status is not free.
 */
@Startup
@ApplicationScoped
class CardOutboxDeadLetterGauge : AbstractOutboxDeadLetterGauge {
    private lateinit var outboxRepository: CardOutboxRepository
    private var domainMetrics: DomainMetrics? = null

    @Inject
    constructor(outboxRepository: CardOutboxRepository, metrics: DomainMetrics) : super(metrics) {
        this.outboxRepository = outboxRepository
        this.domainMetrics = metrics
    }

    @Suppress("ProtectedMemberInFinalClass")
    protected constructor() : super()

    override val service: String = "card-issuance"

    override suspend fun currentDeadLettered(): Long = outboxRepository.countDead()

    /**
     * ADR-0237: the outbox exemption in `check-scheduler-liveness.py` rests on
     * `openbank.outbox.backlog` being its own freshness signal, and that argument does not
     * transfer here — the alert on this gauge is `> 0`, so a dead tick pins it at the boot ZERO
     * and the alert simply never fires. So it carries a real heartbeat.
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
        private const val WORKFLOW_NAME = "card-issuance-outbox-dead-letter-gauge"
    }
}
