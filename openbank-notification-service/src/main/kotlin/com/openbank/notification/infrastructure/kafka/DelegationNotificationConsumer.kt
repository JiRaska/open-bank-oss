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
 * Wires ADR-0232 delegated-access lifecycle events into customer notifications.
 *
 * `openbank-delegation-service` already publishes the full lifecycle onto
 * `openbank.delegation.events` (DelegationEvents.kt) via its transactional outbox; today
 * `openbank-account-service` (its enforcement projection, ADR-0232 D3) and `openbank-audit-service`
 * (its `onBehalfOf` audit trail) are the only consumers. Neither party is told anything — this is
 * a SECOND consumer of the same, already-live topic, not a new event contract.
 *
 * **Which party, per event** — the party who has something to act on, or who is affected:
 *  - `DelegationOffered` -> the **grantee**: they have an offer to accept or decline.
 *  - `DelegationActivated` -> the **grantor**: their offer was accepted.
 *  - `DelegationDeclined` -> the **grantor**: their offer was turned down.
 *  - `DelegationRevoked` -> the **grantee**: their access just ended.
 *  - `DelegationSuspended` / `DelegationReinstated` -> **both**: authority changed at the bank.
 *  - `DelegationRenounced` -> the **grantor**: the grantee ended their access.
 *  - `DelegationExpired` -> **both**: the grant is gone either way.
 *  - Any future/unknown type is deliberately not notified until its recipient semantics are reviewed.
 *
 * **Delivery reuses the real pipeline, in-process.** Rather than re-implement rendering, the
 * consent gate, push/email preference checks, persistence and the outcome-event write, this builds
 * the exact [NotificationRequest] wire shape every other producer sends on
 * `openbank.notification.requests` (see `KafkaNotificationRequestPublisher`,
 * `LoggingNotificationSender`) and hands it to [NotificationConsumer.consume] directly — the same
 * entry point a real Kafka delivery on that topic would reach. That also means the closed
 * variable-schema check (ADR-0176 D1) and the deep-link allow-list still run for every notification
 * built here, exactly as they would for an external producer.
 *
 * **No name in the copy.** [DelegationEvents][com.openbank.delegation.domain.event] carries
 * `grantorPartyId`/`granteePartyId` as UUIDs only — no display name rides the wire (delegation-
 * service's own counterparty-names table, V3, is a read model local to that service). Resolving a
 * name would mean a synchronous cross-service call from an event consumer for a non-critical field,
 * which this fan-out deliberately does not add; the templates read `resourceType` only.
 *
 * **Idempotency**: none, deliberately, matching [NotificationConsumer.consume]'s own documented
 * position — delivery is at-least-once and a redelivery re-persists a fresh notification row, which
 * is "acceptable for notifications (no money path)". A DLQ'd/retried delegation event can therefore
 * produce a duplicate notification on redelivery, same as every other channel into this service.
 *
 * **Failure handling**: a malformed/unparseable record is a poison pill — logged and swallowed so
 * it can never wedge the partition (mirrors [PartyErasureConsumer]). No `dead-letter-queue`
 * `failure-strategy` is configured, matching this service's other two channels
 * (`notification-events-in`, `party-events-in`): [NotificationConsumer.consume] already recovers
 * every failure internally (JSON parse, closed-schema rejection, and `dispatch`'s own
 * `.onFailure().recoverWithUni`) and always completes its `Uni`, so there is nothing left here that
 * would reach a failure-strategy.
 */
@ApplicationScoped
class DelegationNotificationConsumer @Inject constructor(
    private val notificationConsumer: NotificationConsumer,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(DelegationNotificationConsumer::class.java)

    /**
     * Reactive `Uni`, not `suspend` — same reasoning as [NotificationConsumer.consume]'s own KDoc:
     * this method's only real work is delegating into that `Uni`-returning method, and a `suspend`
     * wrapper here would reintroduce exactly the Vert.x-context hazard that method's KDoc documents.
     */
    @Incoming("delegation-events-in")
    // Mirrors PartyErasureConsumer/NotificationConsumer.consume: any malformed record is a poison
    // pill and must be swallowed, not just the JsonProcessingException Jackson usually throws.
    @Suppress("TooGenericExceptionCaught")
    fun consume(payload: String): Uni<Void> {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.warnf(
                e,
                "Dropping unprocessable delegation event (poison pill): %s",
                payload.take(MAX_LOGGED_PAYLOAD_CHARS),
            )
            return Uni.createFrom().voidItem()
        }
        val requests = requestsFor(node, payload)
        if (requests.isEmpty()) return Uni.createFrom().voidItem()
        return requests
            .map { req -> notificationConsumer.consume(objectMapper.writeValueAsString(req)) }
            .reduce { a, b -> a.chain { _: Void? -> b } }
    }

    /** The [NotificationRequest]s this event should raise — zero, one, or two (EXPIRED). */
    private fun requestsFor(node: JsonNode, payload: String): List<NotificationRequest> {
        val eventType = node.path("eventType").asText("")
        val template = TEMPLATE_BY_EVENT_TYPE[eventType]
        if (template == null) {
            // Not an error: future event types stay out until their customer recipient semantics
            // are deliberately reviewed (see class KDoc).
            log.debugf("delegation event %s not in notification scope, skipping", eventType.ifBlank { "?" })
            return emptyList()
        }
        if (eventType == SPEND_CONFIRMED && node.path("sourceService").asText() != DELEGATION_SOURCE_SERVICE) {
            log.warnf("Dropping SpendConfirmed event with an unexpected source service")
            return emptyList()
        }
        val grantId = node.path("aggregateId").asText(null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val grantor = node.path("grantorPartyId").asText(null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val grantee = node.path("granteePartyId").asText(null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (grantId == null || grantor == null || grantee == null) {
            log.warnf(
                "Dropping delegation event %s with missing/unparseable identifiers: %s",
                eventType,
                payload.take(MAX_LOGGED_PAYLOAD_CHARS),
            )
            return emptyList()
        }
        val targets = TARGETS_BY_EVENT_TYPE.getValue(eventType)(grantor, grantee)
        return targets.map { partyId ->
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = template,
                recipient = partyId.toString(),
                variables = if (template == NotificationTemplate.DELEGATION_FIRST_USE) {
                    emptyMap()
                } else {
                    mapOf("resourceType" to node.path("resourceType").asText(""))
                },
                deepLink = "openbank://delegations/$grantId",
                // The grant id, not a freshly minted one: it is the stable identifier a producer
                // owns for this business event (ADR-0239 D1), letting a later outcome event be
                // joined back to the delegation grant that caused it.
                correlationId = grantId,
                deduplicationKey = if (template == NotificationTemplate.DELEGATION_FIRST_USE) grantId else null,
            )
        }
    }

    private companion object {
        /** Cap on the producer-supplied payload echoed into a poison-pill warning (untrusted input). */
        const val MAX_LOGGED_PAYLOAD_CHARS = 300
        const val SPEND_CONFIRMED = "SpendConfirmed"
        const val DELEGATION_SOURCE_SERVICE = "delegation-service"

        val TEMPLATE_BY_EVENT_TYPE: Map<String, NotificationTemplate> = mapOf(
            "DelegationOffered" to NotificationTemplate.DELEGATION_OFFERED,
            "DelegationActivated" to NotificationTemplate.DELEGATION_ACCEPTED,
            "DelegationDeclined" to NotificationTemplate.DELEGATION_DECLINED,
            "DelegationRevoked" to NotificationTemplate.DELEGATION_REVOKED,
            "DelegationSuspended" to NotificationTemplate.DELEGATION_SUSPENDED,
            "DelegationReinstated" to NotificationTemplate.DELEGATION_REINSTATED,
            "DelegationRenounced" to NotificationTemplate.DELEGATION_RENOUNCED,
            "DelegationExpired" to NotificationTemplate.DELEGATION_EXPIRED,
            SPEND_CONFIRMED to NotificationTemplate.DELEGATION_FIRST_USE,
        )

        /** Recipient party id(s) per event type, given (grantor, grantee) — see class KDoc. */
        val TARGETS_BY_EVENT_TYPE: Map<String, (UUID, UUID) -> List<UUID>> = mapOf(
            "DelegationOffered" to { _, grantee -> listOf(grantee) },
            "DelegationActivated" to { grantor, _ -> listOf(grantor) },
            "DelegationDeclined" to { grantor, _ -> listOf(grantor) },
            "DelegationRevoked" to { _, grantee -> listOf(grantee) },
            "DelegationSuspended" to { grantor, grantee -> listOf(grantor, grantee) },
            "DelegationReinstated" to { grantor, grantee -> listOf(grantor, grantee) },
            "DelegationRenounced" to { grantor, _ -> listOf(grantor) },
            "DelegationExpired" to { grantor, grantee -> listOf(grantor, grantee) },
            SPEND_CONFIRMED to { grantor, _ -> listOf(grantor) },
        )
    }
}
