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
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Wires ADR-0232 delegated-access lifecycle events into customer notifications.
 *
 * `openbank-delegation-service` already publishes the full lifecycle onto
 * `openbank.delegation.events` via its transactional outbox. This consumer turns selected events
 * into notifications; enforcement and audit consumers independently read the same topic.
 *
 * **Which party, per event** — the party who has something to act on, or who is affected:
 *  - `DelegationOffered` -> the **grantee**: they have an offer to accept or decline.
 *  - `DelegationActivated` -> the **grantor**: their offer was accepted.
 *  - `DelegationDeclined` -> the **grantor**: their offer was turned down.
 *  - `DelegationRevoked` -> the **grantee**: their access just ended.
 *  - `DelegationSuspended` / `DelegationReinstated` -> **both**: authority changed at the bank.
 *  - `DelegationRenounced` -> the **grantor**: the grantee ended their access.
 *  - `DelegationExpired` -> **both**: the grant is gone either way.
 *  - `StatutoryDelegationProposalOpened` -> each human on the frozen JOINT roster, once per
 *    operation/person. It is only an inbox hint; the signing API rechecks live authority.
 *  - `StatutoryDelegationProposalCancelled` -> a durable operation tombstone and one generic,
 *    replay-safe cancellation alert to each co-signer with a persisted original hint, except the initiator.
 *    A late opened event or retry must not solicit a signature after cancellation.
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
 * **Idempotency**: joint proposal fan-out uses a stable per-operation/person notification key, so
 * a Kafka replay cannot create another notification row for the same representative. This does NOT
 * guarantee exactly-once dispatch: if sending fails after that row commits, [NotificationConsumer]
 * skips the duplicate request. Stale PENDING joint prompts are reclaimed by
 * [com.openbank.notification.infrastructure.JointNotificationRetryJob] with a bounded retry budget;
 * terminal FAILED rows are not replayed as a second delivery. Legacy
 * lifecycle types retain their delivery semantics; first-use and recertification also have keys.
 *
 * **Failure handling**, two kinds, and only the first is handled here. A malformed/unparseable
 * record is a poison pill — logged and swallowed so it can never wedge the partition (mirrors
 * [PartyErasureConsumer]). A *processing* failure is not: [consume] returns the `Uni` it gets from
 * [NotificationConsumer.consume], and that `Uni` FAILS. Its `.onFailure().invoke` only logs, by
 * deliberate design — retrying from the top would persist a second row and re-send, so the single
 * attempt is rethrown and the connector's `failure-strategy` decides.
 *
 * Which makes the configuration load-bearing, and this KDoc used to describe it backwards: it said
 * no `failure-strategy` was configured, "matching this service's other two channels", and that
 * `NotificationConsumer.consume` "always completes its `Uni`". Both were false — #5745 had already
 * given `notification-events-in` and `party-events-in` a DLQ and turned that recovery into a
 * rethrow. The connector default is `fail`, which STOPS the channel, so any transient dispatch
 * failure would have silently ended every delegation notification until a pod restart. #8346 wires
 * the DLQ (`openbank.dlq.notification.delegation-events-in`, nested form in `application.yaml` per
 * issue #686, with its `KafkaTopic` CR and a KafkaUser `Write` grant, since a DLQ send that is
 * denied wedges on the very failure it was added to park).
 *
 * Stated as the mechanism rather than the value on purpose: this class controls that the record is
 * nacked, and `application.yaml` is what answers what the connector then does with it.
 */
@ApplicationScoped
class DelegationNotificationConsumer(
    private val notificationConsumer: NotificationConsumer,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(DelegationNotificationConsumer::class.java)

    @Inject
    constructor(notificationConsumer: NotificationConsumer, objectMapper: ObjectMapper) :
        this(notificationConsumer, objectMapper, Clock.systemUTC())

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
            // Proposal events can carry a human roster: never echo raw Kafka payloads to logs.
            log.warnf("Dropping unprocessable delegation event (poison pill): %s", e.javaClass.simpleName)
            return Uni.createFrom().voidItem()
        }
        if (node.path("eventType").asText() == STATUTORY_PROPOSAL_CANCELLED) {
            val cancellation = statutoryCancellation(node) ?: return Uni.createFrom().voidItem()
            return notificationConsumer.recordJointCancellation(
                cancellation.operationId,
                cancellation.principalPartyId,
                cancellation.actorId,
                cancellation.operationKind,
                cancellation.cancelledAt,
            ).onFailure().invoke { e ->
                log.errorf(e, "Failed to record JOINT proposal cancellation operationId=%s", cancellation.operationId)
            }
        }
        val requests = requestsFor(node)
        if (requests.isEmpty()) return Uni.createFrom().voidItem()
        return requests
            .map { req -> notificationConsumer.consume(objectMapper.writeValueAsString(req)) }
            .reduce { a, b -> a.chain { _: Void? -> b } }
    }

    /** The [NotificationRequest]s this event should raise — a JOINT proposal may reach its whole roster. */
    @Suppress(
        "CyclomaticComplexMethod",
        "ComplexCondition",
        // Event-specific recipient and validation rules must remain visibly adjacent to the event map.
    )
    private fun requestsFor(node: JsonNode): List<NotificationRequest> {
        val eventType = node.path("eventType").asText("")
        if (eventType == STATUTORY_PROPOSAL_OPENED) return statutoryProposalRequests(node)
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
        val reviewDue = eventType == RECERTIFICATION_DUE
        val recertificationId = node.path("recertificationId").asText(null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val audience = node.path("audience").asText("")
        val dueEventIsValid = audience in REVIEW_AUDIENCES && recertificationId != null
        if (
            grantId == null ||
            grantor == null ||
            (!reviewDue && grantee == null) ||
            (reviewDue && !dueEventIsValid)
        ) {
            log.warnf("Dropping delegation event %s with missing/unparseable identifiers", eventType)
            return emptyList()
        }
        val targets = TARGETS_BY_EVENT_TYPE.getValue(eventType)(grantor, grantee)
        return targets.map { partyId ->
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = template,
                recipient = partyId.toString(),
                variables = when (template) {
                    NotificationTemplate.DELEGATION_FIRST_USE -> emptyMap()
                    NotificationTemplate.DELEGATION_RECERTIFICATION_DUE ->
                        mapOf("audience" to audience)
                    else -> mapOf("resourceType" to node.path("resourceType").asText(""))
                },
                deepLink = "openbank://delegations/$grantId",
                // The grant id, not a freshly minted one: it is the stable identifier a producer
                // owns for this business event (ADR-0239 D1), letting a later outcome event be
                // joined back to the delegation grant that caused it.
                correlationId = grantId,
                deduplicationKey = when (template) {
                    NotificationTemplate.DELEGATION_FIRST_USE -> grantId
                    // The cycle, rather than the grant, is the notification idempotency boundary:
                    // a later periodic review must notify again, while an outbox redelivery must not.
                    NotificationTemplate.DELEGATION_RECERTIFICATION_DUE -> recertificationId
                    else -> null
                },
            )
        }
    }

    /** One immutable proposal event fans out to the frozen human roster, never to the company id. */
    @Suppress("CyclomaticComplexMethod") // Every malformed authority/expiry field must fail closed before fan-out.
    private fun statutoryProposalRequests(node: JsonNode): List<NotificationRequest> {
        if (node.path("sourceService").asText() != DELEGATION_SOURCE_SERVICE ||
            node.path("aggregateType").asText() != "StatutoryDelegationOperation" ||
            node.path("version").asLong(-1) != 1L
        ) {
            return emptyList()
        }
        val operationId = canonicalUuid(node.path("aggregateId")) ?: return emptyList()
        val principal = canonicalUuid(node.path("principalPartyId")) ?: return emptyList()
        val initiator = canonicalUuid(node.path("actorId")) ?: return emptyList()
        val expiresAt = runCatching { Instant.parse(node.path("expiresAt").asText()) }.getOrNull()
            ?.takeIf { it.isAfter(clock.instant()) } ?: return emptyList()
        val roster = node.path("representativePartyIds")
        if (!roster.isArray || roster.size() < 2) return emptyList()
        val recipients = roster.map { canonicalUuid(it) ?: return emptyList() }
        if (recipients.size != recipients.distinct().size || initiator !in recipients || principal in recipients) {
            return emptyList()
        }
        val (template, link) = when (node.path("operationKind").asText()) {
            "ISSUE" ->
                NotificationTemplate.JOINT_ISSUANCE_SIGNATURE_REQUESTED to "openbank://delegations/joint-issuance"
            "ACCEPT" ->
                NotificationTemplate.JOINT_ACCEPTANCE_SIGNATURE_REQUESTED to "openbank://delegations/joint-acceptance"
            else -> return emptyList()
        }
        return recipients.map { recipient ->
            NotificationRequest(
                partyId = recipient,
                channel = NotificationChannel.PUSH,
                template = template,
                recipient = recipient.toString(),
                variables = emptyMap(),
                deepLink = link,
                correlationId = operationId,
                deliveryNotAfter = expiresAt,
                // Global notification dedup is on one UUID, so use one stable key per human.
                deduplicationKey = UUID.nameUUIDFromBytes(
                    "statutory-proposal:$operationId:$recipient".toByteArray(Charsets.UTF_8),
                ),
            )
        }
    }

    private fun canonicalUuid(node: JsonNode): UUID? = node.takeIf { it.isTextual }?.asText()?.let { raw ->
        runCatching { UUID.fromString(raw).takeIf { it.toString() == raw } }.getOrNull()
    }

    private data class JointCancellation(
        val operationId: UUID,
        val principalPartyId: UUID,
        val actorId: UUID,
        val operationKind: String,
        val cancelledAt: Instant,
    )

    private fun statutoryCancellation(node: JsonNode): JointCancellation? {
        if (node.path("sourceService").asText() != DELEGATION_SOURCE_SERVICE ||
            node.path("aggregateType").asText() != "StatutoryDelegationOperation" ||
            node.path("version").asLong(-1) != 1L
        ) {
            return null
        }
        val operationId = canonicalUuid(node.path("aggregateId")) ?: return null
        val principal = canonicalUuid(node.path("principalPartyId")) ?: return null
        val actor = canonicalUuid(node.path("actorId"))?.takeIf { it != principal } ?: return null
        val kind = node.path("operationKind").asText().takeIf { it == "ISSUE" || it == "ACCEPT" } ?: return null
        val cancelledAt = runCatching { Instant.parse(node.path("occurredAt").asText()) }.getOrNull()
            ?: return null
        if (!SHA256_HEX.matches(node.path("requestHash").asText()) ||
            !SHA256_HEX.matches(node.path("ruleHash").asText())
        ) {
            return null
        }
        return JointCancellation(operationId, principal, actor, kind, cancelledAt)
    }

    private companion object {
        const val SPEND_CONFIRMED = "SpendConfirmed"
        const val RECERTIFICATION_DUE = "DelegationRecertificationDue"
        const val STATUTORY_PROPOSAL_OPENED = "StatutoryDelegationProposalOpened"
        const val STATUTORY_PROPOSAL_CANCELLED = "StatutoryDelegationProposalCancelled"
        const val DELEGATION_SOURCE_SERVICE = "delegation-service"
        val SHA256_HEX = Regex("[0-9a-f]{64}")
        val REVIEW_AUDIENCES = setOf("PERSONAL", "FOP", "SME", "CORPORATE")

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
            RECERTIFICATION_DUE to NotificationTemplate.DELEGATION_RECERTIFICATION_DUE,
        )

        /** Recipient party id(s) per event type, given (grantor, grantee) — see class KDoc. */
        val TARGETS_BY_EVENT_TYPE: Map<String, (UUID, UUID?) -> List<UUID>> = mapOf(
            "DelegationOffered" to { _, grantee -> listOf(requireNotNull(grantee)) },
            "DelegationActivated" to { grantor, _ -> listOf(grantor) },
            "DelegationDeclined" to { grantor, _ -> listOf(grantor) },
            "DelegationRevoked" to { _, grantee -> listOf(requireNotNull(grantee)) },
            "DelegationSuspended" to { grantor, grantee -> listOf(grantor, requireNotNull(grantee)) },
            "DelegationReinstated" to { grantor, grantee -> listOf(grantor, requireNotNull(grantee)) },
            "DelegationRenounced" to { grantor, _ -> listOf(grantor) },
            "DelegationExpired" to { grantor, grantee -> listOf(grantor, requireNotNull(grantee)) },
            SPEND_CONFIRMED to { grantor, _ -> listOf(grantor) },
            RECERTIFICATION_DUE to { grantor, _ -> listOf(grantor) },
        )
    }
}
