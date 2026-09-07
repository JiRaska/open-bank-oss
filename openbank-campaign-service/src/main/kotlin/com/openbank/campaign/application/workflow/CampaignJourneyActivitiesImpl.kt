// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.application.port.out.BannerPlacementPort
import com.openbank.campaign.application.port.out.BannerPlacementRequest
import com.openbank.campaign.application.port.out.CampaignMetricsPort
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.CreditOfferGatePort
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.NotificationSendPort
import com.openbank.campaign.application.port.out.NotificationSendRequest
import com.openbank.campaign.application.port.out.SendHandoffOutcome
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.application.port.out.StepResolution
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignDelivery
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.DecisionPath
import com.openbank.campaign.domain.model.DecisionPathSelection
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.libs.contact.ContactClass
import com.openbank.libs.contact.ContactDenyReason
import com.openbank.libs.contact.ContactGateDecision
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.MarketingCallSite
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
// TooManyFunctions: one method per activity declared by CampaignJourneyActivities.
// LongParameterList: nine collaborators, each a distinct outbound port this class genuinely drives
// (repositories, the contact gate, two transports, metrics, the ADR-0269 credit floor) plus the
// dry-run switch. Bundling them into a holder would hide which ports a given delivery path touches
// and make the CDI graph less honest, for no reduction in real coupling.
//
// ONE annotation, not two: a second @Suppress on the same declaration replaces the first rather
// than adding to it, so the split version silently stopped suppressing TooManyFunctions.
@Suppress("TooManyFunctions", "LongParameterList")
open class CampaignJourneyActivitiesImpl(
    private val campaigns: CampaignRepository,
    private val enrolments: EnrolmentRepository,
    private val sendLog: SendLogRepository,
    private val contactGate: ContactPolicyGate,
    private val notificationSend: NotificationSendPort,
    private val bannerPlacement: BannerPlacementPort,
    private val metrics: CampaignMetricsPort,
    /**
     * ADR-0269 rule 2. Consulted HERE, in the send path, rather than at enrolment: a journey runs
     * for days, so a party enrolled while healthy can be in arrears by the third step. Consent has
     * no such hole — its revocation is signalled into the running workflow — but distress has no
     * equivalent signal, so an enrolment-time answer would expire the moment it was given.
     */
    private val creditOfferGate: CreditOfferGatePort,
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
) : CampaignJourneyActivities {

    override fun loadDefinition(campaignId: UUID): JourneyDefinition = runBlockingOnWorker {
        val campaign = campaigns.findById(campaignId)
        JourneyDefinition(campaign?.steps ?: emptyList(), campaign?.stopCondition, campaign?.decisions ?: emptyList())
    }

    override fun controlState(campaignId: UUID, partyId: UUID): JourneyControlState = runBlockingOnWorker {
        JourneyControlState(
            campaignState = campaigns.findById(campaignId)?.state,
            goalReached = sendLog.conversionContextFor(campaignId, partyId).alreadyConverted,
        )
    }

    override fun sendsSoFar(campaignId: UUID, partyId: UUID): Int = runBlockingOnWorker {
        sendLog.countSendsForPartyInCampaign(campaignId, partyId)
    }

    override fun delayForStep(campaignId: UUID, partyId: UUID, step: CampaignStep): Long = runBlockingOnWorker {
        val campaign = campaigns.findById(campaignId)
        val variant = if (campaign?.hasContentExperiment == true) {
            enrolments.findByCampaignAndParty(campaignId, partyId)?.contentVariant
        } else {
            null
        }
        campaign?.steps?.firstOrNull { it.order == step.order }?.delayFor(variant) ?: step.delayFor(variant)
    }

    override fun previousDeliveryStatus(campaignId: UUID, partyId: UUID, stepOrder: Int): DeliveryStatus? =
        runBlockingOnWorker {
            sendLog.latestDeliveryStatusBeforeStep(campaignId, partyId, stepOrder)
        }

    override fun deliveryStatusForStep(campaignId: UUID, partyId: UUID, stepOrder: Int): DeliveryStatus? =
        runBlockingOnWorker {
            sendLog.deliveryStatusForStep(campaignId, partyId, stepOrder)
        }

    override fun skipStep(campaignId: UUID, partyId: UUID, stepOrder: Int) = runBlockingOnWorker {
        sendLog.record(
            SendRecord(Ids.newId(), campaignId, partyId, stepOrder, SendOutcome.SKIPPED_CONDITION, Instant.now()),
        )
        metrics.stepResolved(StepResolution.SKIPPED_CONDITION)
        enrolments.findByCampaignAndParty(campaignId, partyId)?.let {
            enrolments.save(it.copy(currentStep = stepOrder + 1))
        }
        Unit
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
    @MarketingCallSite
    internal suspend fun deliverStepGated(campaignId: UUID, partyId: UUID, stepOrder: Int): StepOutcome {
        val campaign = campaigns.findById(campaignId)
            ?: return resolved(StepResolution.CAMPAIGN_CLOSED, StepOutcome.CAMPAIGN_CLOSED)
        campaign.deliveryStateOutcome()?.let { return resolved(resolutionFor(it), it) }
        if (sendLog.conversionContextFor(campaignId, partyId).alreadyConverted) {
            return resolved(StepResolution.GOAL_REACHED, StepOutcome.GOAL_REACHED)
        }
        val step = campaign.steps.firstOrNull { it.order == stepOrder }
            ?: return resolved(StepResolution.STEP_NOT_FOUND, StepOutcome.SUPPRESSED)
        // The assignment is stored on the enrolment before its workflow starts. Do not choose an
        // arm at this point: a retry must send the same treatment, and an audit must explain which
        // wording the person was actually offered.
        val contentVariant = if (campaign.hasContentExperiment) {
            enrolments.findByCampaignAndParty(campaignId, partyId)?.contentVariant
        } else {
            null
        }
        // Before ANY delivery attempt for this step, and therefore before the push fallback too: a
        // customer the distress floor refuses must not receive the fallback either. Checked per
        // step rather than once per journey because the answer changes underneath a running one.
        if (campaign.productKind.isCredit && !creditOfferGate.mayOffer(partyId)) {
            sendLog.record(Ids.newId(), campaignId, partyId, stepOrder, SendOutcome.SUPPRESSED_CREDIT_DISTRESS)
            return resolved(StepResolution.SUPPRESSED_CREDIT_DISTRESS, StepOutcome.SUPPRESSED)
        }
        return deliverEligibleStep(
            StepDeliveryContext(campaign, step, campaignId, partyId, stepOrder, contentVariant),
        )
    }

    /** ADR-0219: a fallback is possible only after a NO_CONSENT decision for the primary channel. */
    private suspend fun deliverEligibleStep(context: StepDeliveryContext): StepOutcome {
        val primary = context.step.primaryDelivery(context.contentVariant)
        val primaryDecision = checkDelivery(context, primary)
        if (primaryDecision == ContactGateDecision.ALLOWED) return handoff(context, primary)
        throwIfGateUnavailable(primaryDecision)

        val fallback = primaryDecision.denyReason
            .takeIf { it == ContactDenyReason.NO_CONSENT }
            ?.let { context.step.pushFallback(context.contentVariant) }
            ?: return recordSuppressed(context, primaryDecision)
        return deliverFallback(context, fallback)
    }

    private suspend fun deliverFallback(context: StepDeliveryContext, fallback: CampaignDelivery): StepOutcome {
        val decision = checkDelivery(context, fallback)
        if (decision == ContactGateDecision.ALLOWED) return handoff(context, fallback)
        throwIfGateUnavailable(decision)
        return recordSuppressed(context, decision)
    }

    private suspend fun checkDelivery(context: StepDeliveryContext, delivery: CampaignDelivery): ContactGateDecision =
        contactGate.check(
            context.partyId,
            contactClassFor(delivery.channel),
            marketingScopeFor(delivery.channel),
            topic = context.campaign.goal,
        )

    // A transport failure must leave a FAILED send row whatever exception the Kafka client raises,
    // then be rethrown so Temporal retries. Narrowing this catch would re-open that audit gap.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun handoff(context: StepDeliveryContext, delivery: CampaignDelivery): StepOutcome {
        // The send-log row id is minted BEFORE the handoff: it is the wire correlation id and
        // joins asynchronous delivery outcome to precisely this attempt (ADR-0239 D1).
        val sendId = Ids.newId()
        if (dryRun) {
            sendLog.record(
                sendId,
                context.campaignId,
                context.partyId,
                context.stepOrder,
                SendOutcome.DRY_RUN,
                delivery.channel,
            )
            // DRY_RUN gets its own outcome value and never shares one with HANDED_OFF. This branch
            // returns SENT and writes a send-log row while nothing leaves the process, and the flag
            // it depends on defaults to TRUE — so a counter that folded the two together would
            // report a fully working campaign in an environment that has emitted nothing, ever.
            metrics.sendAttempted(delivery.channel, SendHandoffOutcome.DRY_RUN)
            return StepOutcome.SENT
        }
        try {
            if (delivery.channel == Channel.BANNER) {
                bannerPlacement.place(
                    BannerPlacementRequest(
                        interactionRef = sendId,
                        partyId = context.partyId,
                        campaignId = context.campaignId,
                        stepOrder = context.stepOrder,
                        template = delivery.template,
                        variables = delivery.variables,
                        deepLink = requireNotNull(delivery.deepLink),
                        inAppSurface = requireNotNull(delivery.inAppSurface),
                    ),
                )
            } else {
                notificationSend.requestDelivery(context.partyId, delivery, sendId)
            }
        } catch (e: Exception) {
            sendLog.record(
                sendId,
                context.campaignId,
                context.partyId,
                context.stepOrder,
                SendOutcome.FAILED,
                delivery.channel,
            )
            metrics.sendAttempted(delivery.channel, SendHandoffOutcome.FAILED)
            throw e
        }
        sendLog.record(
            sendId,
            context.campaignId,
            context.partyId,
            context.stepOrder,
            SendOutcome.SENT,
            delivery.channel,
        )
        // Recorded after the transport returned, so the counter can never claim a hand-off that did
        // not happen. `handed_off`, not `delivered`: this service holds no delivery credentials
        // (ADR-0200 D3), so delivery is not a fact it is in any position to assert.
        metrics.sendAttempted(delivery.channel, SendHandoffOutcome.HANDED_OFF)
        return StepOutcome.SENT
    }

    private suspend fun recordSuppressed(context: StepDeliveryContext, decision: ContactGateDecision): StepOutcome {
        sendLog.record(
            Ids.newId(),
            context.campaignId,
            context.partyId,
            context.stepOrder,
            outcomeFor(decision.denyReason),
        )
        metrics.stepResolved(suppressionFor(decision.denyReason))
        return StepOutcome.SUPPRESSED
    }

    /** Record [resolution] and hand back [outcome] — one call per `return` path, never two. */
    private fun resolved(resolution: StepResolution, outcome: StepOutcome): StepOutcome {
        metrics.stepResolved(resolution)
        return outcome
    }

    override fun advanceStep(campaignId: UUID, partyId: UUID, stepOrder: Int) = runBlockingOnWorker {
        enrolments.findByCampaignAndParty(campaignId, partyId)?.let {
            enrolments.save(it.copy(currentStep = stepOrder + 1))
        }
        Unit
    }

    override fun advanceToStep(campaignId: UUID, partyId: UUID, stepOrder: Int) = runBlockingOnWorker {
        enrolments.findByCampaignAndParty(campaignId, partyId)?.let {
            enrolments.save(it.copy(currentStep = stepOrder))
        }
        Unit
    }

    override fun recordDecisionPath(
        campaignId: UUID,
        partyId: UUID,
        sourceStepOrder: Int,
        path: DecisionPath,
        nextStepOrder: Int,
    ) = runBlockingOnWorker {
        enrolments.findByCampaignAndParty(campaignId, partyId)?.let { enrolment ->
            // Re-read the raw fact rather than have the workflow pass it in: the workflow already
            // called this exact port (deliveryStatusForStep) to choose `path`, and re-deriving the
            // snapshot here keeps this an activity-side-only addition (ADR-0263 Phase A) — neither
            // the workflow code nor the CampaignJourneyActivities interface changes shape, so no
            // Workflow.getVersion gate is needed for it.
            val observedStatus = sendLog.deliveryStatusForStep(campaignId, partyId, sourceStepOrder)
            // Temporal retries an activity at least once. Source order is a graph-node identity,
            // so replacing the same record is idempotent and cannot inflate a person's path.
            val selection = DecisionPathSelection(sourceStepOrder, path, nextStepOrder, Instant.now(), observedStatus)
            val updatedPath = enrolment.decisionPath.filterNot { it.sourceStepOrder == sourceStepOrder } + selection
            enrolments.save(enrolment.copy(currentStep = nextStepOrder, decisionPath = updatedPath))
        }
        Unit
    }

    override fun markCompleted(campaignId: UUID, partyId: UUID) = runBlockingOnWorker {
        enrolments.findByCampaignAndParty(campaignId, partyId)?.let {
            enrolments.save(it.copy(state = EnrolmentState.COMPLETED, completedAt = Instant.now()))
            // Inside the `let`: an enrolment that is not there was not moved to a terminal state,
            // and counting the call rather than the transition would report journeys finishing on a
            // service whose repository is returning nothing.
            metrics.enrolmentTerminal(EnrolmentState.COMPLETED)
        }
        Unit
    }

    override fun markTerminated(campaignId: UUID, partyId: UUID, reason: TerminationReason) = runBlockingOnWorker {
        val state = when (reason) {
            TerminationReason.CONSENT_REVOKED -> EnrolmentState.TERMINATED_CONSENT_REVOKED
            TerminationReason.CAMPAIGN_CLOSED -> EnrolmentState.TERMINATED_CAMPAIGN_CLOSED
            TerminationReason.GOAL_REACHED -> EnrolmentState.COMPLETED_GOAL_REACHED
            TerminationReason.SUPPRESSED -> EnrolmentState.TERMINATED_SUPPRESSED
            TerminationReason.STOPPED_MAX_SENDS -> EnrolmentState.STOPPED_MAX_SENDS
        }
        enrolments.findByCampaignAndParty(campaignId, partyId)?.let {
            enrolments.save(it.copy(state = state, completedAt = Instant.now()))
            metrics.enrolmentTerminal(state)
        }
        Unit
    }

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
        /** ADR-0198 D4: marketing consent is channel-specific, never one service-wide setting. */
        private fun marketingScopeFor(channel: Channel): String = when (channel) {
            Channel.EMAIL -> "MARKETING_COMMS_EMAIL"
            Channel.PUSH -> "MARKETING_COMMS_PUSH"
            Channel.BANNER -> "MARKETING_COMMS_INAPP"
        }

        /** Banner placement is a first-party in-app impression, never an outbound send. */
        private fun contactClassFor(channel: Channel): ContactClass = when (channel) {
            Channel.BANNER -> ContactClass.PROMOTIONAL_IMPRESSION
            Channel.EMAIL, Channel.PUSH -> ContactClass.OUTBOUND_SEND
        }

        /** Metric tag for a gate denial, one value per policy reason the log already separates. */
        private fun suppressionFor(reason: ContactDenyReason?): StepResolution = when (reason) {
            ContactDenyReason.SUPPRESSED_LIST -> StepResolution.SUPPRESSED_LIST
            ContactDenyReason.SEND_CAP_REACHED -> StepResolution.SUPPRESSED_CAP
            ContactDenyReason.QUIET_HOURS -> StepResolution.SUPPRESSED_QUIET_HOURS
            ContactDenyReason.NO_CONSENT -> StepResolution.SUPPRESSED_CONSENT
            ContactDenyReason.IMPRESSION_BUDGET_REACHED,
            ContactDenyReason.GATE_UNAVAILABLE,
            null,
            -> StepResolution.SUPPRESSED_OTHER
        }

        /** Metric tag for a campaign-state resolution reached before any gate ran. */
        private fun resolutionFor(outcome: StepOutcome): StepResolution = when (outcome) {
            StepOutcome.CAMPAIGN_PAUSED -> StepResolution.CAMPAIGN_PAUSED
            StepOutcome.GOAL_REACHED -> StepResolution.GOAL_REACHED
            StepOutcome.SENT, StepOutcome.SUPPRESSED, StepOutcome.CAMPAIGN_CLOSED -> StepResolution.CAMPAIGN_CLOSED
        }

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

/** Immutable state shared by the primary attempt and the only permitted PUSH fallback. */
private data class StepDeliveryContext(
    val campaign: Campaign,
    val step: CampaignStep,
    val campaignId: UUID,
    val partyId: UUID,
    val stepOrder: Int,
    val contentVariant: com.openbank.campaign.domain.model.ContentVariant?,
)

/** Only ACTIVE campaigns may enter delivery; every other state has a durable workflow outcome. */
private fun Campaign.deliveryStateOutcome(): StepOutcome? = when (state) {
    CampaignState.ACTIVE -> null
    CampaignState.PAUSED -> StepOutcome.CAMPAIGN_PAUSED
    CampaignState.CLOSED,
    CampaignState.DRAFT,
    CampaignState.PENDING_APPROVAL,
    -> StepOutcome.CAMPAIGN_CLOSED
}

/** A gate outage is retriable infrastructure state, never a customer-policy suppression. */
private fun throwIfGateUnavailable(decision: ContactGateDecision) {
    if (decision.denyReason == ContactDenyReason.GATE_UNAVAILABLE) {
        throw ContactGateUnavailableException("contact gate state unavailable — activity will retry")
    }
}

// Two helpers as top-level privates rather than members: CampaignJourneyActivitiesImpl sits at
// detekt's TooManyFunctions threshold of 11, which fires AT the limit, so the branch-condition
// activities (#3585) had to buy their room somewhere.

/**
 * Write one send-log row.
 *
 * `id` is a parameter rather than minted here: on the ALLOWED path it was already published as the
 * correlation id, and a second id would be a row nothing can ever correlate back to. A gate-denied
 * send never reached notification-service, so its id correlates with nothing and its delivery
 * status stays PENDING forever — correctly: no message was ever handed off.
 */
private suspend fun SendLogRepository.record(
    id: UUID,
    campaignId: UUID,
    partyId: UUID,
    stepOrder: Int,
    outcome: SendOutcome,
    channel: Channel? = null,
) = record(SendRecord(id, campaignId, partyId, stepOrder, outcome, Instant.now(), channel = channel))

// The recipient address is resolved by notification-service from party data; the campaign never
// carries an e-mail address itself (ADR-0200 D3 — no PII duplication).
private fun recipientFor(partyId: UUID): String = partyId.toString()

/** Build the cross-service command outside the gate's nested delivery branch. */
private suspend fun NotificationSendPort.requestDelivery(partyId: UUID, delivery: CampaignDelivery, sendId: UUID) {
    requestSend(
        NotificationSendRequest(
            partyId = partyId,
            channel = delivery.channel,
            template = delivery.template,
            recipient = recipientFor(partyId),
            variables = delivery.variables,
            correlationId = sendId,
            deepLink = delivery.deepLink,
            // A send-log id is opaque to the device but is the one durable campaign-owned row
            // that a future attribution boundary can validate against. EMAIL never exposes it.
            interactionRef = sendId.takeIf { delivery.channel == Channel.PUSH },
        ),
    )
}
