// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.ConversionCatalog
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.ExperimentCohort
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.libs.domain.identifiers.Ids
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Records a campaign conversion from a product event (ADR-0245).
 *
 * A campaign's `goal` is prose; this is the machine side. The consumer watches the topics named by
 * [ConversionCatalog], and when an event says a party did the thing a campaign existed to cause, it
 * writes one [SendOutcome.CONVERTED] row against that party's enrolment.
 *
 * **Reads the event type from the header AND the payload, on purpose.** The two producers put it in
 * different places: `AccountCreatedEvent` serialises its own `eventType` field, while `CardIssued`
 * has no such field and the type travels only as the outbox `ce-type` header
 * (`OutboxKafkaHeaders.HEADER_EVENT_TYPE`). Reading one alone would match nothing on the other
 * producer — and `openbank.cards.events` is a shared topic, so without the check a limit change
 * would count as a conversion.
 *
 * **Failure mode is a missing conversion, never a wrong send.** This consumer only ever appends an
 * outcome row; it cannot cause, suppress or alter a message. If it lags or dies, conversions are
 * late or absent — which is why the console must never render "0 conversions" and "conversion
 * tracking is behind" as the same thing.
 */
@ApplicationScoped
class ConversionConsumer(
    private val enrolments: EnrolmentRepository,
    private val campaigns: CampaignRepository,
    private val sendLog: SendLogRepository,
    private val journeys: JourneySignaller,
    private val mapper: ObjectMapper,
) {
    private val log = Logger.getLogger(ConversionConsumer::class.java)

    @Incoming("account-conversions-in")
    suspend fun onAccountEvent(message: Message<String>) = handle(message, "openbank.accounts.account.created")

    @Incoming("card-conversions-in")
    suspend fun onCardEvent(message: Message<String>) = handle(message, "openbank.cards.events")

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun handle(message: Message<String>, topic: String) {
        try {
            val event = try {
                mapper.readTree(message.payload)
            } catch (e: Exception) {
                log.errorf(e, "Unparseable %s event — dropped", topic)
                return
            }

            val partyId = event.path("partyId").takeIf { !it.isMissingNode && !it.isNull }
                ?.let { runCatching { UUID.fromString(it.asText()) }.getOrNull() }
                ?: return

            val eventType = headerEventType(message) ?: event.path("eventType").asText().ifBlank { null }
            val candidates = ConversionCatalog.forTopic(topic).filterValues { it.matches(eventType) }
            if (candidates.isEmpty()) return

            // `occurredAt` is the event's own clock where it has one. A missing or epoch value is
            // treated as "now" rather than as 1970: several event types in the fleet default
            // occurredAt to Instant.EPOCH, and silently reading that as the event time would push
            // every such conversion outside every attribution window — a defect that looks exactly
            // like "nobody converted".
            val occurredAt = runCatching { Instant.parse(event.path("occurredAt").asText()) }
                .getOrNull()
                ?.takeIf { it.isAfter(Instant.EPOCH) }
                ?: Instant.now()

            recordAttributedConversion(partyId, candidates, occurredAt)
        } finally {
            message.ack()
        }
    }

    @Suppress("ReturnCount")
    private suspend fun recordAttributedConversion(
        partyId: UUID,
        candidates: Map<String, ConversionCatalog.Rule>,
        occurredAt: Instant,
    ) {
        val eligible = mutableListOf<AttributionCandidate>()
        for (enrolment in enrolments.listByParty(partyId)) {
            attributionCandidate(enrolment, candidates, occurredAt)
                ?.let(eligible::add)
        }

        // One product outcome gets one owner: deterministic last-touch among eligible campaigns.
        // The old loop credited every overlapping campaign, so a single account opening inflated
        // conversion totals across the portfolio. Keep an already-converted winner in the ranking:
        // on Kafka redelivery we must return, not shift the same event to the second-place campaign.
        val winner = eligible.maxWithOrNull(
            compareBy<AttributionCandidate> { it.firstSentAt }.thenBy { it.campaignId },
        ) ?: return
        if (winner.alreadyConverted) return

        sendLog.record(
            SendRecord(
                // Ids.newId() is UUIDv7 (ADR-0106): a send-log row is durable and indexed, so a
                // time-ordered id keeps the primary key from scattering writes across the B-tree.
                id = Ids.newId(),
                campaignId = winner.campaignId,
                partyId = partyId,
                stepOrder = winner.stepOrder,
                outcome = SendOutcome.CONVERTED,
                occurredAt = occurredAt,
            ),
        )
        // The durable row above is the correctness fallback read before every later step. The signal
        // interrupts a long Temporal delay immediately so the customer is not persuaded after acting.
        // A holdout deliberately has no workflow to interrupt. Recording its observed product
        // outcome is precisely the point of keeping it enrolled in the experiment.
        if (winner.cohort == ExperimentCohort.TREATMENT) {
            journeys.signalGoalReached(winner.campaignId, partyId)
        }
        log.infof("Conversion recorded: campaign=%s party=%s", winner.campaignId, partyId)
    }

    @Suppress("ReturnCount")
    private suspend fun attributionCandidate(
        enrolment: Enrolment,
        candidates: Map<String, ConversionCatalog.Rule>,
        occurredAt: Instant,
    ): AttributionCandidate? {
        val campaign = campaigns.findById(enrolment.campaignId) ?: return null
        val rule = campaign.conversionRule?.let { candidates[it] } ?: return null
        val context = sendLog.conversionContextFor(campaign.id, enrolment.partyId)
        // Treatment attribution starts only after a real send. A holdout has no send by design;
        // its equivalent start is its immutable enrolment assignment, not an invented handoff.
        val attributionStart = when (enrolment.experimentCohort) {
            ExperimentCohort.TREATMENT -> context.firstSentAt ?: return null
            ExperimentCohort.HOLDOUT -> enrolment.startedAt
        }
        if (occurredAt.isBefore(attributionStart) ||
            occurredAt.isAfter(attributionStart.plus(rule.attributionWindow))
        ) {
            return null
        }
        return AttributionCandidate(
            campaign.id,
            enrolment.currentStep,
            attributionStart,
            context.alreadyConverted,
            enrolment.experimentCohort,
        )
    }

    /** The outbox `ce-type` header, when the record carries Kafka metadata at all. */
    private fun headerEventType(message: Message<String>): String? =
        message.getMetadata(IncomingKafkaRecordMetadata::class.java)
            .orElse(null)
            ?.headers
            ?.lastHeader("ce-type")
            ?.value()
            ?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
}

private data class AttributionCandidate(
    val campaignId: UUID,
    val stepOrder: Int,
    val firstSentAt: Instant,
    val alreadyConverted: Boolean,
    val cohort: ExperimentCohort,
)
