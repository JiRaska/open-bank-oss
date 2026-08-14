// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.domain.model.FunnelStage
import com.openbank.onboarding.infrastructure.client.PartyServiceClient
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * Daily cleanup of abandoned onboarding registrations.
 *
 * A registration is considered abandoned when it has been stuck in an early KYC stage
 * (KYC_OPEN or KYC_DOCUMENTS_REQUIRED) for more than [ABANDONED_DAYS] days without activity.
 * Abandoned parties are suspended via party-service, which emits a PartyStatusChanged event
 * that the onboarding projection will pick up → funnelStage transitions to BLOCKED.
 *
 * Compliance note: suspended parties can be reactivated manually by an operator from the
 * admin-UI onboarding cockpit (ADR-0068).
 *
 * ## Liveness heartbeat (ADR-0237)
 *
 * "No abandoned registrations found" is both the healthy quiet day and what a schedule that
 * stopped firing looks like from the outside — the `HR000068` class that left five schedulers in
 * this repo never running (#2148, #2187). [DomainMetrics.registerWorkflowLiveness] publishes the
 * last-success age so the ADR-0237 staleness rule and `openbank-control-liveness-sentinel` can
 * tell them apart.
 *
 * [WorkflowLivenessRecorder.recordSuccess] marks **the sweep's own pass**, not the fate of each
 * party: the empty case records success (an empty pass is a successful pass, and withholding the
 * heartbeat there would make a healthy job read as stale), and so does a pass in which individual
 * `suspendParty` calls failed — those are already counted and logged per item, and gating the
 * heartbeat on them would let one permanently unsuspendable party masquerade as a dead scheduler,
 * hiding the signal this gauge exists to carry. A failure of [OnboardingRepository.listStuckBefore]
 * itself propagates uncaught, so no heartbeat is recorded — which is the intended distinction.
 *
 * Registration hangs off [StartupEvent] rather than `@PostConstruct` because `@ApplicationScoped`
 * is lazy: a `@PostConstruct` would first run when the cron first fires, up to a day after boot,
 * leaving the gauge absent for that whole window — and absent is not the same signal as stale.
 */
@ApplicationScoped
class AbandonedRegistrationCleaner(private val clock: Clock) {

    companion object {
        private val LOG: Logger = Logger.getLogger(AbandonedRegistrationCleaner::class.java)
        private const val ABANDONED_DAYS = 30L
        private val EARLY_STAGES = listOf(FunnelStage.KYC_OPEN, FunnelStage.KYC_DOCUMENTS_REQUIRED)
        private const val WORKFLOW_NAME = "onboarding-abandoned-registration-cleanup"
        private val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }

    @Inject
    lateinit var onboardingRepo: OnboardingRepository

    @Inject
    @RestClient
    lateinit var partyClient: PartyServiceClient

    @Inject
    lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    /** Runs daily at 02:00 UTC. Cron expression: second minute hour day-of-month month day-of-week */
    @Scheduled(cron = "0 0 2 * * ?", identity = "abandoned-registration-cleanup")
    suspend fun expireAbandoned() {
        val cutoff = clock.instant().minus(ABANDONED_DAYS, ChronoUnit.DAYS)
        val stale = onboardingRepo.listStuckBefore(EARLY_STAGES, cutoff)

        if (stale.isEmpty()) {
            liveness?.recordSuccess()
            LOG.debug("No abandoned registrations found")
            return
        }

        LOG.infof("Found %d abandoned registrations older than %d days — suspending", stale.size, ABANDONED_DAYS)

        var suspended = 0
        var failed = 0
        for (record in stale) {
            try {
                // system expiry (≠ officer rejection): party → SUSPENDED, onboarding → BLOCKED
                partyClient.suspendParty(
                    record.partyId,
                    mapOf("kycStatus" to "EXPIRED"),
                ).awaitSuspending()
                suspended++
                LOG.infof("Suspended abandoned party %s (stuck since %s)", record.partyId, record.updatedAt)
            } catch (e: Exception) {
                failed++
                LOG.warnf("Failed to suspend abandoned party %s: %s", record.partyId, e.message)
            }
        }

        liveness?.recordSuccess()
        LOG.infof("Abandoned registration cleanup: %d suspended, %d failed", suspended, failed)
    }
}
