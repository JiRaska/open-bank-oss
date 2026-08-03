// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.NotificationSendPort
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.libs.contact.ContactClass
import com.openbank.libs.contact.ContactDenyReason
import com.openbank.libs.contact.ContactGateDecision
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.UUID

/**
 * Delivery activities (ADR-0200 D2/D3/D6). Suppression, caps, quiet hours and consent are one
 * ADR-0219 gate call (#3656) — evaluated in the gate's ordering (suppression list → send cap →
 * quiet hours → live consent pull, ADR-0195's no-cached-consent rule) — and the send itself is
 * only ever a request onto `openbank.notification.requests` — this service holds no delivery
 * credentials (D3). A gate OUTAGE is not a policy outcome: [ContactGateUnavailableException]
 * rethrows so the Temporal activity retries instead of terminating a journey on a transient blip.
 */
@ApplicationScoped
open class CampaignJourneyActivitiesImpl(
    private val campaigns: CampaignRepository,
    private val enrolments: EnrolmentRepository,
    private val sendLog: SendLogRepository,
    private val contactGate: ContactPolicyGate,
    private val notificationSend: NotificationSendPort,
    /**
     * When true, a step runs every gate and then stops short of the transport: nothing is emitted to
     * notification-service on any channel, and the send log records DRY_RUN.
     *
     * Defaults to TRUE — the safe direction. A non-production environment that forgets to set this
     * would otherwise mail real people, and the failure is discovered by the recipient. Production
     * sets it to false explicitly, which is a reviewable line in a manifest rather than an omission.
     */
    @ConfigProperty(name = "openbank.campaign.dry-run", defaultValue = "true")
    private val dryRun: Boolean,
    @ConfigProperty(name = "openbank.campaign.marketing-scope", defaultValue = "MARKETING_COMMS_EMAIL")
    private val marketingScope: String,
) : CampaignJourneyActivities {

    override fun loadSteps(campaignId: UUID): List<CampaignStep> = runBlockingOnWorker {
        campaigns.findById(campaignId)?.steps ?: emptyList()
    }

    // The publish failure below is caught broadly on purpose: the point is that NO handoff failure
    // may leave the send log without a row, and narrowing the catch would re-open the gap for
    // whichever exception type the Kafka client happens to raise next (#3581). It is rethrown, so
    // nothing is swallowed.
    override fun deliverStep(campaignId: UUID, partyId: UUID, stepOrder: Int): StepOutcome = runBlockingOnWorker {
        deliverStepGated(campaignId, partyId, stepOrder)
    }

    /**
     * The gate decision and its mapping, split from the Vert.x bridge so a plain unit test can
     * drive it without a Quarkus runtime ([com.openbank.campaign.application.workflow.CampaignJourneyActivitiesTest]); the activity entry
     * point above stays a one-line delegate.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun deliverStepGated(campaignId: UUID, partyId: UUID, stepOrder: Int): StepOutcome {
        val campaign = campaigns.findById(campaignId) ?: return StepOutcome.SUPPRESSED
        val step = campaign.steps.firstOrNull { it.order == stepOrder } ?: return StepOutcome.SUPPRESSED

        // ADR-0219 (#3656): one gate call wraps the suppression list, the frequency cap, quiet
        // hours and the live consent pull, in the gate's ordering.
        return when (
            val decision = contactGate.check(
                partyId,
                ContactClass.OUTBOUND_SEND,
                marketingScope,
                topic = campaign.goal,
            )
        ) {
            ContactGateDecision.ALLOWED -> {
                // Dry run stops HERE, after every gate above has run. Deliberately not earlier: the
                // point of a rehearsal is that suppression, cap, quiet hours and consent are all
                // exercised exactly as they would be, so a journey that would have been suppressed
                // still reports SUPPRESSED and not DRY_RUN.
                if (dryRun) {
                    record(campaignId, partyId, stepOrder, SendOutcome.DRY_RUN)
                } else {
                    // ADR-0200 D3 — delivery goes through notification-service, never direct.
                    //
                    // The order below is the fix for issue #3581: the send log may only be written
                    // AFTER the handoff has been observed to succeed, and a handoff that failed must
                    // leave a FAILED row rather than nothing. What SENT claims here is exactly
                    // "notification-service accepted the request", not "the customer received it".
                    try {
                        notificationSend.requestSend(partyId, step.channel, step.template, recipientFor(partyId), step.variables)
                    } catch (e: Exception) {
                        record(campaignId, partyId, stepOrder, SendOutcome.FAILED)
                        // Rethrown on purpose: Temporal retries the activity, and the FAILED row
                        // above is the durable evidence that this attempt happened. FAILED rows do
                        // not consume the frequency cap (SENT only), so a retry is not penalised.
                        throw e
                    }
                    record(campaignId, partyId, stepOrder, SendOutcome.SENT)
                }
                StepOutcome.SENT
            }
            else -> {
                if (decision.denyReason == ContactDenyReason.GATE_UNAVAILABLE) {
                    throw ContactGateUnavailableException("contact gate state unavailable — activity will retry")
                }
                record(campaignId, partyId, stepOrder, outcomeFor(decision.denyReason))
                StepOutcome.SUPPRESSED
            }
        }
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

    // The recipient address is resolved by notification-service from party data; the campaign
    // never carries an e-mail address itself (ADR-0200 D3 — no PII duplication).
    private fun recipientFor(partyId: UUID): String = partyId.toString()

    /**
     * Every repository here is reactive Panache, and a Temporal activity runs on a plain worker
     * thread that carries NO Vert.x context — a bare `runBlocking` around a reactive Panache call
     * throws `HR000068 No current Vertx context found`, which would fail every activity in this
     * class and deliver nothing (same shape as the @Scheduled sweep in #2148/#2187). Bridge
     * through [VertxContextSupport] instead, exactly as `DomesticPaymentActivitiesImpl.vtx` does
     * — safe here precisely because an activity thread is never an event loop. `internal open` so
     * tests can substitute a plain `runBlocking` (the FxActivitiesImplTest seam).
     */
    internal open fun <T> runBlockingOnWorker(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    companion object {
        /** Gate deny reasons that are POLICY outcomes, recorded per step (ADR-0219). */
        private fun outcomeFor(reason: ContactDenyReason?): SendOutcome = when (reason) {
            ContactDenyReason.SUPPRESSED_LIST -> SendOutcome.SUPPRESSED_LIST
            ContactDenyReason.SEND_CAP_REACHED -> SendOutcome.SUPPRESSED_CAP
            ContactDenyReason.QUIET_HOURS -> SendOutcome.SUPPRESSED_QUIET_HOURS
            ContactDenyReason.NO_CONSENT -> SendOutcome.SUPPRESSED_CONSENT
            ContactDenyReason.IMPRESSION_BUDGET_REACHED,
            ContactDenyReason.GATE_UNAVAILABLE,
            null,
            -> SendOutcome.FAILED
        }
    }
}

/** Thrown when the contact gate cannot reach its state — a retry signal, never a policy outcome. */
class ContactGateUnavailableException(message: String) : RuntimeException(message)
