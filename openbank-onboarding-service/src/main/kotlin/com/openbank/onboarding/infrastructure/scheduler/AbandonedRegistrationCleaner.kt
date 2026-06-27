// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.scheduler

import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.domain.model.FunnelStage
import com.openbank.onboarding.infrastructure.client.PartyServiceClient
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
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
 */
@ApplicationScoped
class AbandonedRegistrationCleaner(private val clock: Clock) {

    companion object {
        private val LOG: Logger = Logger.getLogger(AbandonedRegistrationCleaner::class.java)
        private const val ABANDONED_DAYS = 30L
        private val EARLY_STAGES = listOf(FunnelStage.KYC_OPEN, FunnelStage.KYC_DOCUMENTS_REQUIRED)
    }

    @Inject
    lateinit var onboardingRepo: OnboardingRepository

    @Inject
    @RestClient
    lateinit var partyClient: PartyServiceClient

    /** Runs daily at 02:00 UTC. Cron expression: second minute hour day-of-month month day-of-week */
    @Scheduled(cron = "0 0 2 * * ?", identity = "abandoned-registration-cleanup")
    suspend fun expireAbandoned() {
        val cutoff = clock.instant().minus(ABANDONED_DAYS, ChronoUnit.DAYS)
        val stale = onboardingRepo.listStuckBefore(EARLY_STAGES, cutoff)

        if (stale.isEmpty()) {
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

        LOG.infof("Abandoned registration cleanup: %d suspended, %d failed", suspended, failed)
    }
}
