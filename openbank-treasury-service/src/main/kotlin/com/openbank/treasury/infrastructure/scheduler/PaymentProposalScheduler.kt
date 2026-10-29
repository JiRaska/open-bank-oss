// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.usecase.PaymentService
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration

/**
 * ADR-0344 D6: proposes payments for the FIXED flows inside the cut-off window, hourly (a proposal is
 * keyed by its flows, so a repeat proposes nothing twice). Release stays a human act.
 * A `suspend fun`: a plain `@Scheduled` method has no Vert.x context (check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class PaymentProposalScheduler(
    private val payments: PaymentService,
    private val domainMetrics: DomainMetrics,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.treasury.payments.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    private val log = Logger.getLogger(PaymentProposalScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(1))
    }

    @Scheduled(
        cron = "{openbank.treasury.payments.cron:0 25 * * * ?}",
        identity = "treasury-payment-proposals",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun run() {
        if (!enabled) return
        runCatching { payments.propose(AccountingClock.bank(clock).today()) }
            .onSuccess { r ->
                r.failures.forEach { log.error("payment proposal failed", it) }
                r.unaddressable.forEach { log.warnf("payment not proposed: %s", it) }
                if (r.failures.isEmpty()) liveness?.recordSuccess()
            }
            .onFailure { log.error("payment proposal run failed", it) }
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-payment-proposals"
    }
}
