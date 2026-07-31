// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.NotificationSendPort
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Delivery activities (ADR-0200 D2/D3/D6). The order inside [deliverStep] is deliberate and
 * load-bearing: suppression (frequency cap, quiet hours) is evaluated BEFORE consent (D6: "a
 * suppression is not a consent question"), consent is re-checked live immediately before the send
 * (D2 pull mechanism, ADR-0195's no-cached-consent rule), and the send itself is only ever a
 * request onto `openbank.notification.requests` — this service holds no delivery credentials (D3).
 */
@ApplicationScoped
// activity wiring: repos + ports + policy config in one place, per the worker-registrar pattern
@Suppress("LongParameterList")
class CampaignJourneyActivitiesImpl(
    private val campaigns: CampaignRepository,
    private val enrolments: EnrolmentRepository,
    private val sendLog: SendLogRepository,
    private val consentCheck: ConsentCheckPort,
    private val notificationSend: NotificationSendPort,
    @ConfigProperty(name = "openbank.campaign.max-sends-per-party-per-week", defaultValue = "2")
    private val maxSendsPerWeek: Int,
    @ConfigProperty(name = "openbank.campaign.quiet-hours-start", defaultValue = "21")
    private val quietHoursStart: Int,
    @ConfigProperty(name = "openbank.campaign.quiet-hours-end", defaultValue = "8")
    private val quietHoursEnd: Int,
    @ConfigProperty(name = "openbank.campaign.marketing-scope", defaultValue = "MARKETING_COMMS_EMAIL")
    private val marketingScope: String,
) : CampaignJourneyActivities {

    override fun loadSteps(campaignId: UUID): List<CampaignStep> = runBlockingOnWorker {
        campaigns.findById(campaignId)?.steps ?: emptyList()
    }

    override fun deliverStep(campaignId: UUID, partyId: UUID, stepOrder: Int): StepOutcome = runBlockingOnWorker {
        val campaign = campaigns.findById(campaignId) ?: return@runBlockingOnWorker StepOutcome.SUPPRESSED
        val step =
            campaign.steps.firstOrNull { it.order == stepOrder } ?: return@runBlockingOnWorker StepOutcome.SUPPRESSED

        // ADR-0200 D6 — suppression before consent.
        val weekAgo = Instant.now().epochSecond - SECONDS_PER_WEEK
        if (sendLog.countRecentForParty(partyId, weekAgo) >= maxSendsPerWeek) {
            record(campaignId, partyId, stepOrder, SendOutcome.SUPPRESSED_CAP)
            return@runBlockingOnWorker StepOutcome.SUPPRESSED
        }
        if (isQuietHours()) {
            record(campaignId, partyId, stepOrder, SendOutcome.SUPPRESSED_QUIET_HOURS)
            return@runBlockingOnWorker StepOutcome.SUPPRESSED
        }

        // ADR-0200 D2 — live consent pull, immediately before the send.
        if (!consentCheck.hasActiveConsent(partyId, marketingScope)) {
            record(campaignId, partyId, stepOrder, SendOutcome.SUPPRESSED_CONSENT)
            return@runBlockingOnWorker StepOutcome.SUPPRESSED
        }

        // ADR-0200 D3 — delivery goes through notification-service, never direct.
        notificationSend.requestSend(partyId, step.template, recipientFor(partyId), step.variables)
        record(campaignId, partyId, stepOrder, SendOutcome.SENT)
        StepOutcome.SENT
    }

    override fun advanceStep(campaignId: UUID, partyId: UUID, stepOrder: Int) = runBlockingOnWorker {
        enrolments.findByCampaignAndParty(campaignId, partyId)?.let {
            enrolments.save(it.copy(currentStep = stepOrder + 1))
        }
        Unit
    }

    override fun markCompleted(campaignId: UUID, partyId: UUID) = runBlockingOnWorker {
        enrolments.findByCampaignAndParty(campaignId, partyId)?.let {
            enrolments.save(it.copy(state = EnrolmentState.COMPLETED, completedAt = Instant.now()))
        }
        Unit
    }

    override fun markTerminated(campaignId: UUID, partyId: UUID, reason: TerminationReason) = runBlockingOnWorker {
        val state = when (reason) {
            TerminationReason.CONSENT_REVOKED -> EnrolmentState.TERMINATED_CONSENT_REVOKED
            TerminationReason.SUPPRESSED -> EnrolmentState.TERMINATED_SUPPRESSED
        }
        enrolments.findByCampaignAndParty(campaignId, partyId)?.let {
            enrolments.save(it.copy(state = state, completedAt = Instant.now()))
        }
        Unit
    }

    private suspend fun record(campaignId: UUID, partyId: UUID, stepOrder: Int, outcome: SendOutcome) {
        sendLog.record(SendRecord(Ids.newId(), campaignId, partyId, stepOrder, outcome, Instant.now()))
    }

    /** Quiet hours wrap midnight: 21→8 means "hour >= 21 OR hour < 8" in the platform zone. */
    private fun isQuietHours(): Boolean {
        val hour = java.time.LocalTime.now(ZoneId.of("Europe/Prague")).hour
        return if (quietHoursStart > quietHoursEnd) {
            hour >= quietHoursStart || hour < quietHoursEnd
        } else {
            hour >= quietHoursStart && hour < quietHoursEnd
        }
    }

    // The recipient address is resolved by notification-service from party data; the campaign
    // never carries an e-mail address itself (ADR-0200 D3 — no PII duplication).
    private fun recipientFor(partyId: UUID): String = partyId.toString()

    /**
     * Every repository here is reactive Panache, and a Temporal activity runs on a plain worker
     * thread that carries NO Vert.x context — a bare `runBlocking` around a reactive Panache call
     * throws `HR000068 No current Vertx context found`, which would fail every activity in this
     * class and deliver nothing (same shape as the @Scheduled sweep in #2148/#2187). Bridge
     * through [VertxContextSupport] instead, exactly as `DomesticPaymentActivitiesImpl.vtx` does
     * — safe here precisely because an activity thread is never an event loop.
     */
    private fun <T> runBlockingOnWorker(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    companion object {
        private const val SECONDS_PER_WEEK = 7L * 24 * 3600
    }
}
