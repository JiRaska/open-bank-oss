// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.swift.application.port.out.InstitutionTransferMetrics
import com.openbank.swift.application.port.out.InstitutionTransferRepository
import com.openbank.swift.application.port.out.ResolutionOutcome
import com.openbank.swift.application.usecase.InstitutionTransferService
import com.openbank.swift.domain.institution.InstitutionTransferStatus
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
 * Resolves bank-own transfers nobody else will move: a submission left SUBMITTING /
 * SUBMISSION_UNKNOWN (no redelivery came), and a transfer SENT on `ACSP` whose settlement the
 * scheme confirms later. Every [every] it takes at most [batchSize] such transfers last changed
 * more than [minAge] ago and hands each to [InstitutionTransferService.resolveDue] — the status
 * query by UETR, the single-worker resend claim and MANUAL_RECONCILIATION on ambiguity are the
 * service's, so the sweeper adds no second policy.
 *
 * A `suspend fun` (the fleet rule: a plain `@Scheduled` method carries no Vert.x context and its
 * reactive Panache calls fail). Per-item failures are isolated: one transfer that cannot be
 * resolved is counted FAILED and the batch moves on. A concurrent mover (another pod, a
 * redelivered release) loses on the transfer's optimistic version, never doubling an event.
 * Liveness: registered at boot, recorded after every completed pass (ADR-0237).
 */
@ApplicationScoped
class InstitutionTransferResolutionSweeper(
    private val repo: InstitutionTransferRepository,
    private val service: InstitutionTransferService,
    private val metrics: InstitutionTransferMetrics,
    private val domainMetrics: DomainMetrics,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.swift.institution-transfer.resolution.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.swift.institution-transfer.resolution.min-age", defaultValue = "PT2M")
    private val minAge: Duration,
    @ConfigProperty(name = "openbank.swift.institution-transfer.resolution.batch-size", defaultValue = "50")
    private val batchSize: Int,
) {
    private val log = Logger.getLogger(InstitutionTransferResolutionSweeper::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "\${openbank.swift.institution-transfer.resolution.every:60s}",
        delayed = "\${openbank.swift.institution-transfer.resolution.initial-delay:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "swift-institution-transfer-resolution",
    )
    suspend fun sweep() {
        if (!enabled) return
        val due = repo.findDueForResolution(Instant.now(clock).minus(minAge), batchSize)
        for (transfer in due) metrics.resolution(resolveOne(transfer))
        liveness?.recordSuccess()
    }

    // TooGenericExceptionCaught: isolation is the point — whatever one transfer throws (scheme
    // down, lost optimistic race, a defect) must not stop the rest of the batch. It is logged
    // and counted FAILED; the transfer stays due and is retried next pass.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveOne(
        transfer: com.openbank.swift.domain.institution.InstitutionTransfer,
    ): ResolutionOutcome = try {
        when (service.resolveDue(transfer).status) {
            InstitutionTransferStatus.SETTLED -> ResolutionOutcome.SETTLED
            InstitutionTransferStatus.REJECTED -> ResolutionOutcome.REJECTED
            InstitutionTransferStatus.SENT -> ResolutionOutcome.STILL_SENT
            InstitutionTransferStatus.MANUAL_RECONCILIATION -> ResolutionOutcome.MANUAL_RECONCILIATION
            else -> ResolutionOutcome.UNRESOLVED
        }
    } catch (e: Exception) {
        log.warnf(e, "Resolution of bank-own transfer %s failed; retried next pass", transfer.paymentId)
        ResolutionOutcome.FAILED
    }

    private companion object {
        const val WORKFLOW_NAME = "swift-institution-transfer-resolution"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
