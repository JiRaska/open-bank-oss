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
 * Tells a customer when someone else gains or loses access to their accounts (#8432).
 *
 * `CONSENT_GRANTED` and `CONSENT_REVOKED` have been declared, rendered and given required
 * variables since notification-service was written, and **nothing ever emitted either**. A TPP
 * could be granted ninety days of access to a customer's balances and transactions and the
 * customer was told nothing — `GET /customer/v1/consents` reports it accurately, but only to
 * someone who thinks to go and look. That is the wrong way round for the one consent event a
 * customer most needs to notice they did not make.
 *
 * **A second consumer of an already-live topic, not a new producer.** consent-service publishes
 * the full lifecycle onto `openbank.consent.events` through its transactional outbox
 * (`ConsentRepositoryImpl.outboxMessage`), so the notification rides a record written in the same
 * transaction as the consent itself and cannot be lost the way a best-effort emit alongside the
 * commit can. campaign-service already consumes this topic; this is a third reader.
 *
 * **Only account-access consents notify.** `ConsentScope` mixes PSD2/agent access scopes with pure
 * data-processing preferences (marketing channels, RUM telemetry, credit-offer processing), and
 * both kinds raise the same two events. The templates say access to *account data* was granted, so
 * firing them for a marketing toggle would be both untrue and noise the customer generated
 * themselves seconds earlier. The decision is not re-derived here: consent-service publishes
 * `accountAccess`, computed from its own `Consent.GDPR_ONLY_SCOPES`, so adding a preference scope
 * there cannot silently turn into a security push here. An event without the field is dropped
 * rather than guessed — see [requestFor].
 *
 * **Grant means ACTIVE, not requested.** consent-service raises `ConsentGranted` only from
 * `persistActivation`, i.e. once SCA has completed (or immediately, for the SCA-exempt GDPR-only
 * consents, which this consumer then filters out anyway). There is no PENDING_SCA notification.
 *
 * **`ConsentSuperseded`, `ConsentExpired` and `ConsentRejected` deliberately do not notify.**
 * Superseded is not a withdrawal — access continues under a newer consent covering the same
 * grantee and the same scopes (#6487) — and telling someone their access ended would be false.
 * Expiry and rejection are outcomes the customer either scheduled or just declined in a ceremony
 * they were present for; both are worth a customer-facing decision before they become a push, and
 * neither has a template that fits.
 *
 * **No deep link.** `openbank://consents` is not in [MobileDeepLink]'s closed allow-list and no
 * app route was verified for it, so the push carries none rather than a tap that goes nowhere.
 *
 * Delivery, idempotency and poison-pill handling all follow [DelegationNotificationConsumer]
 * exactly: build the wire-shape [NotificationRequest] every producer sends on
 * `openbank.notification.requests` and hand it to [NotificationConsumer.consume] in-process, so
 * the consent gate, closed variable-schema check and preference checks all still run.
 */
@ApplicationScoped
class ConsentNotificationConsumer @Inject constructor(
    private val notificationConsumer: NotificationConsumer,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(ConsentNotificationConsumer::class.java)

    /** Reactive `Uni`, not `suspend` — see [DelegationNotificationConsumer.consume]. */
    @Incoming("consent-events-in")
    // Mirrors DelegationNotificationConsumer/PartyErasureConsumer: any malformed record is a
    // poison pill and must be swallowed, not just the JsonProcessingException Jackson throws.
    @Suppress("TooGenericExceptionCaught")
    fun consume(payload: String): Uni<Void> {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.warnf(
                e,
                "Dropping unprocessable consent event (poison pill): %s",
                payload.take(MAX_LOGGED_PAYLOAD_CHARS),
            )
            return Uni.createFrom().voidItem()
        }
        val request = requestFor(node, payload) ?: return Uni.createFrom().voidItem()
        return notificationConsumer.consume(objectMapper.writeValueAsString(request))
    }

    /** The [NotificationRequest] this event should raise, or null when it should raise none. */
    private fun requestFor(node: JsonNode, payload: String): NotificationRequest? {
        val eventType = node.path("eventType").asText("")
        val template = TEMPLATE_BY_EVENT_TYPE[eventType]
        if (template == null) {
            // Not an error: Superseded/Expired/Rejected, and any future type, stay out until their
            // customer-facing semantics are deliberately reviewed (see class KDoc).
            log.debugf("consent event %s not in notification scope, skipping", eventType.ifBlank { "?" })
            return null
        }

        // Fail CLOSED on the field that decides whether this is a security event at all. A consent
        // event produced before this field existed cannot be classified, and guessing either way is
        // worse than silence: `true` pushes account-access wording at a marketing toggle, `false`
        // hides a third party gaining access. Only reachable during the rollout in which
        // consent-service starts publishing it, and only for records already in flight.
        val accountAccess = node.path("accountAccess")
        if (!accountAccess.isBoolean) {
            log.warnf(
                "Dropping consent event %s with no usable accountAccess flag: %s",
                eventType,
                payload.take(MAX_LOGGED_PAYLOAD_CHARS),
            )
            return null
        }
        if (!accountAccess.booleanValue()) {
            log.debugf("consent event %s is a data-processing preference, not notifying", eventType)
            return null
        }

        val consentId = node.path("aggregateId").asText(null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val partyId = node.path("partyId").asText(null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (consentId == null || partyId == null) {
            log.warnf(
                "Dropping consent event %s with missing/unparseable identifiers: %s",
                eventType,
                payload.take(MAX_LOGGED_PAYLOAD_CHARS),
            )
            return null
        }

        return NotificationRequest(
            partyId = partyId,
            channel = NotificationChannel.PUSH,
            template = template,
            // Informational for PUSH — delivery is by registered device token, not by this value.
            recipient = partyId.toString(),
            variables = mapOf("scope" to scopeText(node)),
            deepLink = null,
            // The consent id, not a freshly minted one: the stable identifier the producer owns for
            // this business event (ADR-0239 D1), so a later delivery outcome joins back to it.
            correlationId = consentId,
        )
    }

    /**
     * The `scope` variable the two templates require, rendered from the event's `scopes` array.
     *
     * Sorted so the same grant always reads the same way — a `Set<ConsentScope>` has no stable
     * serialisation order, and an unsorted join would let two notifications about one consent list
     * its scopes differently. Never empty: `Consent`'s own `init` requires at least one scope, and
     * an event that somehow carried none would still have to satisfy the closed variable-schema
     * check downstream, so the fallback names the situation instead of sending a blank.
     */
    private fun scopeText(node: JsonNode): String {
        val scopes = node.path("scopes").mapNotNull { it.asText(null) }.filter { it.isNotBlank() }.sorted()
        return if (scopes.isEmpty()) "unspecified" else scopes.joinToString(", ")
    }

    private companion object {
        /** Cap on the producer-supplied payload echoed into a poison-pill warning (untrusted input). */
        const val MAX_LOGGED_PAYLOAD_CHARS = 300

        val TEMPLATE_BY_EVENT_TYPE: Map<String, NotificationTemplate> = mapOf(
            "ConsentGranted" to NotificationTemplate.CONSENT_GRANTED,
            "ConsentRevoked" to NotificationTemplate.CONSENT_REVOKED,
        )
    }
}
