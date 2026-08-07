// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.usecase.TriggeredEnrolment
import com.openbank.campaign.application.usecase.TriggeredEnrolmentService
import com.openbank.campaign.domain.model.TriggerCatalog
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Enrols a party into a waiting campaign the moment a product event says they qualify.
 *
 * The third way into a campaign, after the manual endpoint and the cadence, and the only one that
 * reacts rather than polls: "welcome someone who just opened an account" is a day late as a nightly
 * sweep.
 *
 * **A separate consumer from [ConversionConsumer], on the same two topics.** They look alike and
 * are deliberately not merged. A conversion is a measurement that can only ever append a row —
 * that consumer's KDoc says its failure mode is "a missing conversion, never a wrong send". This
 * one *causes* sends. Fusing them would put an outbound-message path behind a class documented as
 * unable to produce one, and would tie their consumer offsets together, so a lag in measurement
 * would delay real customer contact. The cost is reading each topic twice, which is a cheap price
 * for keeping the blast radius of the two apart.
 *
 * **Everything a normal journey enforces still applies.** This only creates the enrolment; the
 * Temporal journey then runs the same steps, with the same ADR-0219 contact gate — suppression,
 * send caps, quiet hours and the live consent pull — before anything reaches a customer. A trigger
 * cannot skip a gate, only decide when the journey starts.
 */
@ApplicationScoped
class EnrolmentTriggerConsumer(
    private val campaigns: CampaignRepository,
    private val triggered: TriggeredEnrolmentService,
    private val mapper: ObjectMapper,
) {
    private val log = Logger.getLogger(EnrolmentTriggerConsumer::class.java)

    @Incoming("account-triggers-in")
    suspend fun onAccountEvent(message: Message<String>) = handle(message, "openbank.accounts.account.created")

    @Incoming("card-triggers-in")
    suspend fun onCardEvent(message: Message<String>) = handle(message, "openbank.cards.events")

    @Suppress("TooGenericExceptionCaught")
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

            // Header first, payload second — the two producers put the type in different places
            // (see TriggerCatalog). `openbank.cards.events` is shared, so without this a limit
            // change would enrol the party into a card-issuance campaign.
            val eventType = headerEventType(message) ?: event.path("eventType").asText().ifBlank { null }
            val triggers = TriggerCatalog.matching(topic, eventType)
            if (triggers.isEmpty()) return

            for (trigger in triggers) {
                for (campaign in campaigns.findActiveByTrigger(trigger)) {
                    enrol(campaign.id, partyId, trigger)
                }
            }
        } finally {
            // Acked in every case, including the failures logged below. A poison event that cannot
            // enrol anyone would otherwise be redelivered forever and stall the partition, blocking
            // every later party's trigger behind it. The cost of the alternative is worse than the
            // event being lost: this consumer starts journeys, so a wedged partition is silent
            // non-delivery for everyone.
            message.ack()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun enrol(campaignId: UUID, partyId: UUID, trigger: String) {
        try {
            when (val outcome = triggered.enrol(campaignId, partyId)) {
                TriggeredEnrolment.ENROLLED ->
                    log.infof("Trigger %s enrolled party=%s into campaign=%s", trigger, partyId, campaignId)
                // The ordinary answers. A product event arrives for every party in the bank and
                // almost none are in any given segment, so these are logged at debug — at info they
                // would be the loudest thing in the service and would bury the enrolments.
                TriggeredEnrolment.NOT_IN_SEGMENT,
                TriggeredEnrolment.ALREADY_ENROLLED,
                TriggeredEnrolment.NOT_ACTIVE,
                ->
                    log.debugf("Trigger %s: campaign=%s party=%s -> %s", trigger, campaignId, partyId, outcome)
                // These two mean the definition is broken rather than the party unqualified.
                TriggeredEnrolment.SEGMENT_GONE,
                TriggeredEnrolment.CAMPAIGN_GONE,
                ->
                    log.warnf("Trigger %s: campaign=%s party=%s -> %s", trigger, campaignId, partyId, outcome)
            }
        } catch (e: Exception) {
            // One campaign's failure must not cost the others their enrolment of this same party.
            log.errorf(e, "Trigger %s failed for campaign=%s party=%s", trigger, campaignId, partyId)
        }
    }

    /** The outbox `ce-type` header, when the record carries Kafka metadata at all. */
    private fun headerEventType(message: Message<String>): String? =
        message.getMetadata(IncomingKafkaRecordMetadata::class.java)
            .orElse(null)
            ?.headers
            ?.lastHeader("ce-type")
            ?.value()
            ?.toString(Charsets.UTF_8)
}
