// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.ConversionCatalog
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
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

            enrolments.listByParty(partyId).forEach { enrolment ->
                recordIfConverted(enrolment.campaignId, enrolment.currentStep, partyId, candidates, occurredAt)
            }
        } finally {
            message.ack()
        }
    }

    @Suppress("ReturnCount")
    private suspend fun recordIfConverted(
        campaignId: UUID,
        stepOrder: Int,
        partyId: UUID,
        candidates: Map<String, ConversionCatalog.Rule>,
        occurredAt: Instant,
    ) {
        val campaign = campaigns.findById(campaignId) ?: return
        val rule = campaign.conversionRule?.let { candidates[it] } ?: return

        val context = sendLog.conversionContextFor(campaignId, partyId)
        // A campaign gets credit only from the moment it actually said something. Measuring from
        // enrolment would credit a campaign for a decision made while it sat behind a delay or a
        // quiet-hours suppression, having contributed nothing.
        val firstSend = context.firstSentAt ?: return
        if (occurredAt.isBefore(firstSend)) return
        if (occurredAt.isAfter(firstSend.plus(rule.attributionWindow))) return
        if (context.alreadyConverted) return

        sendLog.record(
            SendRecord(
                id = UUID.randomUUID(),
                campaignId = campaignId,
                partyId = partyId,
                stepOrder = stepOrder,
                outcome = SendOutcome.CONVERTED,
                occurredAt = occurredAt,
            ),
        )
        log.infof("Conversion recorded: campaign=%s party=%s rule=%s", campaignId, partyId, campaign.conversionRule)
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
