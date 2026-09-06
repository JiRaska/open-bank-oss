// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.usecase.TriggeredEnrolment
import com.openbank.campaign.application.usecase.TriggeredEnrolmentService
import com.openbank.campaign.domain.model.TriggerCatalog
import com.openbank.libs.messaging.EventRetry
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

    /**
     * Ack is now the SUCCESS path, not the only path.
     *
     * The record is acked when the work is done, and when the payload is a poison pill no
     * redelivery could ever fix (unparseable JSON, no usable `partyId`, no matching trigger) —
     * those are dropped deliberately and named as such. Anything else is a dependency failing, not
     * the event: [EventRetry] retries it a bounded number of times and then rethrows, and the
     * record is NACKED so the platform, rather than a log line nobody pages on, owns the outcome.
     *
     * The previous `finally { message.ack() }` acked every one of those, so a campaigns-DB blip
     * or a Temporal outage meant the party was never enrolled and never would be — `reset: latest`
     * rules out replay and nothing in lag, the DLQ or any dashboard showed a thing.
     *
     * **What the nack does next is the CONNECTOR's decision, not this class's.** The handler's
     * contract ends at "the work did not happen, and the platform was told". What follows depends
     * entirely on the channel's configured `failure-strategy`: `dead-letter-queue` parks the record
     * on the channel's DLQ topic for replay, while SmallRye's default `fail` stops the channel
     * instead. Both are better than an ack, which loses the party silently; they are not the same
     * incident, so read the channel's config in `application.yaml` before predicting one.
     * (#5751 wires `failure-strategy: dead-letter-queue` for `account-triggers-in` and
     * `card-triggers-in`, with explicit `openbank.dlq.campaign.<channel>` topics.)
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun handle(message: Message<String>, topic: String) {
        val event = try {
            mapper.readTree(message.payload)
        } catch (e: Exception) {
            // Poison pill: replaying it fails identically forever, so acking is the right answer.
            log.errorf(e, "Unparseable %s event — dropped", topic)
            message.ack()
            return
        }

        val partyId = event.path("partyId").takeIf { !it.isMissingNode && !it.isNull }
            ?.let { runCatching { UUID.fromString(it.asText()) }.getOrNull() }
        if (partyId == null) {
            // Nothing to enrol. Not a fault: the shared topics carry records with no party at all.
            message.ack()
            return
        }

        // Header first, payload second — the two producers put the type in different places
        // (see TriggerCatalog). `openbank.cards.events` is shared, so without this a limit
        // change would enrol the party into a card-issuance campaign.
        val eventType = headerEventType(message) ?: event.path("eventType").asText().ifBlank { null }
        val triggers = TriggerCatalog.matching(topic, eventType)
        if (triggers.isEmpty()) {
            message.ack()
            return
        }

        try {
            enrolAll(triggers, partyId)
        } catch (e: Exception) {
            message.nack(e)
            return
        }
        message.ack()
    }

    /**
     * Attempts every (trigger, campaign) pair for this party, then rethrows the FIRST transient
     * failure once they have all been tried.
     *
     * The original per-campaign catch existed so one campaign's failure did not cost the others
     * their enrolment of the same party; that intent is kept by continuing the loop. What changes
     * is the ending: the failure is no longer discarded. Redelivery is safe because
     * `TriggeredEnrolmentService.enrol` returns `ALREADY_ENROLLED` for anything that did succeed.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun enrolAll(triggers: Collection<String>, partyId: UUID) {
        var firstFailure: Exception? = null
        for (trigger in triggers) {
            for (campaign in campaigns.findActiveByTrigger(trigger)) {
                val failure = enrolOrFailure(campaign.id, partyId, trigger)
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun enrolOrFailure(campaignId: UUID, partyId: UUID, trigger: String): Exception? = try {
        enrol(campaignId, partyId, trigger)
        null
    } catch (e: Exception) {
        e
    }

    private suspend fun enrol(campaignId: UUID, partyId: UUID, trigger: String) {
        val outcome = EventRetry.withRetry(log, "Triggered enrolment", "campaign=$campaignId party=$partyId") {
            triggered.enrol(campaignId, partyId)
        }
        when (outcome) {
            TriggeredEnrolment.ENROLLED ->
                log.infof("Trigger %s enrolled party=%s into campaign=%s", trigger, partyId, campaignId)
            // The ordinary answers. A product event arrives for every party in the bank and
            // almost none are in any given segment, so these are logged at debug — at info they
            // would be the loudest thing in the service and would bury the enrolments.
            TriggeredEnrolment.NOT_IN_SEGMENT,
            TriggeredEnrolment.ALREADY_ENROLLED,
            TriggeredEnrolment.NOT_ACTIVE,
            // ADR-0269 rule 1. A settled answer, not a fault: the party qualified and the bank is
            // not allowed to say so. Logged at the same level as the other ordinary outcomes and
            // ACKED — a nack would redeliver the record forever against a consent that only the
            // customer can change, and retrying a refusal is not how it becomes an enrolment. The
            // count lives in the SUPPRESSED_CREDIT_CONSENT metric, not in this log line.
            TriggeredEnrolment.NO_CREDIT_CONSENT,
            ->
                log.debugf("Trigger %s: campaign=%s party=%s -> %s", trigger, campaignId, partyId, outcome)
            // These two mean the definition is broken rather than the party unqualified.
            TriggeredEnrolment.SEGMENT_GONE,
            TriggeredEnrolment.CAMPAIGN_GONE,
            ->
                log.warnf("Trigger %s: campaign=%s party=%s -> %s", trigger, campaignId, partyId, outcome)
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
