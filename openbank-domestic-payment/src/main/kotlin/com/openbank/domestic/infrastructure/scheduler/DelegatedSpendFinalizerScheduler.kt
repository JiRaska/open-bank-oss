// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.scheduler

import com.openbank.domestic.application.port.`in`.FinalizeAbsentDelegatedSpendUseCase
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.Startup
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Converts old PENDING projections into permanent absence tombstones.
 *
 * The repository owns the row lock and outbox transaction. This scheduler is `suspend`: Quarkus
 * gives only suspend scheduled methods the Vert.x context required by reactive Panache.
 */
@ApplicationScoped
@Startup
class DelegatedSpendFinalizerScheduler(
    private val useCase: FinalizeAbsentDelegatedSpendUseCase,
    @ConfigProperty(name = "openbank.domestic.delegated-spend-finalizer.enabled", defaultValue = "false")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.domestic.delegated-spend-finalizer.grace-period", defaultValue = "PT10M")
    private val gracePeriod: Duration,
    @ConfigProperty(name = "openbank.domestic.delegated-spend-finalizer.batch-limit", defaultValue = "100")
    private val batchLimit: Int,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(DelegatedSpendFinalizerScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    init {
        require(!gracePeriod.isZero && !gracePeriod.isNegative) { "Finalizer grace period must be positive" }
        require(batchLimit > 0) { "Finalizer batch limit must be positive" }
    }

    /** Registers only for an enabled control; a disabled finalizer must not impersonate a healthy one. */
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "{openbank.domestic.delegated-spend-finalizer.interval}",
        delayed = "{openbank.domestic.delegated-spend-finalizer.initial-delay}",
        identity = "delegated-spend-finalizer",
    )
    suspend fun finalizeAbsent() {
        if (!enabled) return
        val finalized = useCase.finalizeBefore(Instant.now(clock).minus(gracePeriod), batchLimit)
        liveness?.recordSuccess()
        if (finalized > 0) log.infof("Finalized %d delegated spend reservation(s) without a payment", finalized)
    }

    private companion object {
        const val WORKFLOW_NAME = "domestic-delegated-spend-finalizer"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
