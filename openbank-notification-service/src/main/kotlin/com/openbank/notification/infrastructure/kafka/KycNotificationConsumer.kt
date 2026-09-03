// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.notification.application.NotificationConsumer
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Closes #8432's sharpest gap: the KYC outcome notifications existed end-to-end — templates
 * rendered, SECURITY-classified, allow-listed — and had NO producer. `openbank-kyc-service`
 * already publishes `KYC_CASE_APPROVED` / `KYC_CASE_REJECTED` onto `openbank.kyc.events` via its
 * transactional outbox (`KycEvents`, since #4007 the ONLY publisher on that topic); this is a
 * further consumer of that already-live topic, not a new event contract and not a second
 * emitter inside kyc-service (the bare post-commit emitter shape #4007 removed there).
 *
 * Recipient is always the data subject: the envelope's `partyId` is the person whose identity
 * verification concluded, and they are exactly who must be told.
 *
 * **The rejection reason is NOT taken from the event — deliberately.** `KYC_REJECTED`'s closed
 * variable schema (ADR-0176 D1) requires a `reason`, and the KycEvents envelope carries none:
 * the reviewer's reason lives in `KycCase.notes`, a ČNB-audited internal field that was never
 * meant for the customer. Copying internal review notes into a customer push would be a PII
 * and process leak, so the notification sends a fixed customer-safe reason and points at
 * support; the template's own copy already ends with "Please contact support."
 *
 * **No deep link.** `MobileDeepLink` is a closed allow-list and has no KYC entry; rather than
 * grow the allow-list for an app route this service cannot verify, the notification carries
 * none (null is allowed).
 *
 * Delivery reuses the real pipeline in-process (same as [DelegationNotificationConsumer]): the
 * exact [NotificationRequest] wire shape handed to [NotificationConsumer.consume], so the
 * closed variable-schema check, the SECURITY classification and the outcome-event write all run
 * unchanged.
 *
 * **Idempotency / failure handling**: identical to [DelegationNotificationConsumer] — delivery
 * is at-least-once and a redelivery re-persists a fresh row (acceptable for notifications, no
 * money path); a malformed record is a poison pill, logged and swallowed, never wedging the
 * partition.
 */
@ApplicationScoped
class KycNotificationConsumer @Inject constructor(
    private val notificationConsumer: NotificationConsumer,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(KycNotificationConsumer::class.java)

    /**
     * Reactive `Uni`, not `suspend` — same reasoning as [DelegationNotificationConsumer]:
     * delegating into a `Uni`-returning method, and a `suspend` wrapper would reintroduce the
     * Vert.x-context hazard documented on [NotificationConsumer.consume].
     */
    @Incoming("kyc-events-in")
    // Mirrors the sibling consumers: any malformed record is a poison pill and must be
    // swallowed, not just the JsonProcessingException Jackson usually throws.
    @Suppress("TooGenericExceptionCaught")
    fun consume(payload: String): Uni<Void> {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.warnf(
                e,
                "Dropping unprocessable KYC event (poison pill): %s",
                payload.take(MAX_LOGGED_PAYLOAD_CHARS),
            )
            return Uni.createFrom().voidItem()
        }
        val request = requestFor(node, payload) ?: return Uni.createFrom().voidItem()
        return notificationConsumer.consume(objectMapper.writeValueAsString(request))
    }

    /** The [NotificationRequest] this event should raise, or null when out of scope. */
    private fun requestFor(node: JsonNode, payload: String): NotificationRequest? {
        val eventType = node.path("eventType").asText("")
        val template = TEMPLATE_BY_EVENT_TYPE[eventType]
        if (template == null) {
            // Not an error: KYC_CASE_OPENED / STATUS_CHANGED and any future type stay
            // un-notified until their customer recipient semantics are deliberately reviewed.
            log.debugf("KYC event %s not in notification scope, skipping", eventType.ifBlank { "?" })
            return null
        }
        val partyId = node.path("partyId").asText(null)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        // `kycCaseId`, not `aggregateId`: the flat envelope on the wire (KycEvents, pinned by
        // the provider pacts) names the case id `kycCaseId`; `aggregateId` exists only on the
        // typed KycEvent object inside kyc-service, never on the topic.
        val caseId = node.path("kycCaseId").asText(null)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        if (partyId == null || caseId == null) {
            log.warnf(
                "Dropping KYC event %s with missing/unparseable identifiers: %s",
                eventType,
                payload.take(MAX_LOGGED_PAYLOAD_CHARS),
            )
            return null
        }
        return NotificationRequest(
            partyId = partyId,
            channel = NotificationChannel.PUSH,
            template = template,
            recipient = partyId.toString(),
            variables = VARIABLES_BY_EVENT_TYPE.getValue(eventType),
            deepLink = null, // no KYC entry in MobileDeepLink's closed allow-list — see KDoc
            // The KYC case id, not a freshly minted one: the stable identifier of the business
            // event (ADR-0239 D1), so a later outcome event joins back to this case.
            correlationId = caseId,
        )
    }

    private companion object {
        /** Cap on the producer-supplied payload echoed into a poison-pill warning (untrusted input). */
        const val MAX_LOGGED_PAYLOAD_CHARS = 300

        val TEMPLATE_BY_EVENT_TYPE: Map<String, NotificationTemplate> = mapOf(
            "KYC_CASE_APPROVED" to NotificationTemplate.KYC_APPROVED,
            "KYC_CASE_REJECTED" to NotificationTemplate.KYC_REJECTED,
        )

        val VARIABLES_BY_EVENT_TYPE: Map<String, Map<String, String>> = mapOf(
            "KYC_CASE_APPROVED" to emptyMap(),
            // Customer-safe, fixed — the internal reviewer reason (KycCase.notes) must not
            // leave the bank; see the class KDoc.
            "KYC_CASE_REJECTED" to mapOf(
                "reason" to "the submitted information could not be verified",
            ),
        )
    }
}
