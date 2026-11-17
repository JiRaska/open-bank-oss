// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.transaction.infrastructure.schedule

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock

/** Recovers the commit-before-Temporal-start gap; the workflow id is the durable dispatch key. */
@ApplicationScoped
class BranchPaymentRecoveryScheduler(
    private val transactions: com.openbank.transaction.infrastructure.persistence.repository.PendingBranchPayments,
    private val dispatch: com.openbank.transaction.application.workflow.BranchPaymentDispatch,
    private val clock: Clock,
    private val metrics: com.openbank.libs.observability.DomainMetrics,
    @ConfigProperty(name = "openbank.transaction.branch-recovery.interval", defaultValue = "PT10S")
    private val interval: java.time.Duration,
    @ConfigProperty(name = "openbank.transaction.branch-recovery.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    private var liveness: com.openbank.libs.observability.WorkflowLivenessRecorder? = null

    @jakarta.annotation.PostConstruct
    fun register() {
        if (enabled) liveness = metrics.registerWorkflowLiveness("branch-payment-recovery", interval)
    }

    @Scheduled(
        every = "${'$'}{openbank.transaction.branch-recovery.interval:PT10S}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun recover() {
        if (!enabled) return
        val cutoff = clock.instant().minusSeconds(DISPATCH_GRACE_SECONDS)
        var pending = transactions.findPending(cutoff, null)
        while (pending.isNotEmpty()) {
            for (id in pending) dispatch.start(id)
            pending = transactions.findPending(cutoff, pending.last())
        }
        liveness?.recordSuccess()
    }

    private companion object {
        const val DISPATCH_GRACE_SECONDS = 60L
    }
}
