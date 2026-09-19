// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.notification.application.NotificationConsumer
import com.openbank.notification.domain.model.MobileDeepLink
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Multi-signature approval notifications (#10281): the consumer of `delegation-service`'s
 * approval-events topic.
 *
 * **Consumed schema — v1, tolerant reader.** One JSON object per record:
 * `eventId` (UUID, required), `type` (required), `occurredAt`, `approvalId` (UUID, required),
 * `entityPartyId`, `entityName`, `kind`, `amount?` (decimal string or number), `currency?`,
 * `payeeName?`, `initiatorPartyId` (UUID, required), `initiatorName`, `recipientPartyIds[]`
 * (UUIDs), `expiresAt` (ISO-8601 instant), `reason?`, plus two optional fields this side reads if
 * present: `schemaVersion` (int, default 1) and `locale` (`cs`/`en`, default `cs`). Unknown fields
 * are ignored and a later `schemaVersion` is read with the v1 fields, because the producer may
 * only evolve the topic additively (ADR-0006). A record missing a required field, or one that is
 * not JSON, is a poison pill: logged by key names only, counted, and acked.
 *
 * **Who hears what.**
 *  - `APPROVAL_REQUESTED` -> `APPROVAL_REQUIRED` to every recipient EXCEPT the initiator (they
 *    signed as they created it and have nothing to act on).
 *  - `APPROVAL_COMPLETED` / `APPROVAL_REJECTED` / `APPROVAL_EXPIRED` / `PAYMENT_RELEASE_FAILED`
 *    -> the matching template to the recipients AND the initiator (the initiator is the person
 *    most affected by the outcome and may not be in the producer's recipient list).
 *  - `APPROVAL_SIGNED` and `PAYMENT_RELEASED` are deliberately NOT notified: a signature is visible
 *    as progress in the app, and a release is the expected end of a COMPLETED request — pushing
 *    both would double every successful payment. Any other type is ignored until reviewed.
 *
 * **Idempotent on `eventId`.** Each (eventId, recipient) pair gets a deterministic name-based UUID
 * as the notification's `deduplicationKey`, which `NotificationConsumer` persists against the
 * partial unique index `uq_notifications_deduplication_key`. A redelivered record — or the whole
 * topic replayed — therefore creates no second inbox row and sends no second push. The key is per
 * RECIPIENT, not per event, because the index is global: one key per event would let only the
 * first co-signer be notified.
 *
 * **Failure handling.** Because the dedup key makes a re-run a no-op for the recipients already
 * recorded, a transient failure is retried in-process a bounded number of times (unlike
 * [DelegationNotificationConsumer], whose requests carry no key and so could double-send). If it
 * still fails the returned `Uni` fails, the record is nacked, and the connector's configured
 * `failure-strategy` decides what follows (`application.yaml`).
 *
 * **Push vs inbox.** Every request is `PUSH` with the deep link
 * `openbank://business/approvals/{approvalId}`. The persisted notification row IS the in-app inbox
 * entry (unread until the app marks it read), so a party with no registered device still sees it
 * in the inbox; the push itself reports `accepted` from the provider, never `delivered`.
 */
@ApplicationScoped
class ApprovalNotificationConsumer @Inject constructor(
    private val notificationConsumer: NotificationConsumer,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = Logger.getLogger(ApprovalNotificationConsumer::class.java)

    /** Reactive `Uni`, not `suspend` — same reasoning as [NotificationConsumer.consume]. */
    @Incoming("approval-events-in")
    // Any malformed record is a poison pill and must be swallowed, not only JsonProcessingException.
    @Suppress("TooGenericExceptionCaught")
    fun consume(payload: String): Uni<Void> {
        val node = try {
            objectMapper.readTree(payload)?.takeIf { it.isObject }
        } catch (e: Exception) {
            log.warnf("Dropping unparseable approval event (%s)", e.javaClass.simpleName)
            null
        }
        if (node == null) {
            count(type = "?", disposition = DISPOSITION_MALFORMED)
            return Uni.createFrom().voidItem()
        }
        val type = node.path("type").asText("")
        val template = TEMPLATE_BY_TYPE[type]
        if (template == null) {
            count(type = if (type in KNOWN_UNNOTIFIED_TYPES) type else "other", disposition = DISPOSITION_IGNORED)
            log.debugf("approval event type %s not notified", type.ifBlank { "?" })
            return Uni.createFrom().voidItem()
        }
        val event = parse(node)
        if (event == null) {
            count(type = type, disposition = DISPOSITION_MALFORMED)
            return Uni.createFrom().voidItem()
        }
        val requests = requestsFor(type, template, event)
        count(type = type, disposition = DISPOSITION_HANDED_OFF, amount = requests.size.toDouble())
        if (requests.isEmpty()) return Uni.createFrom().voidItem()
        return requests
            .map { req -> handOff(req) }
            .reduce { a, b -> a.chain { _: Void? -> b } }
    }

    /**
     * One recipient into the shared pipeline, retried on a transient failure. Safe ONLY because
     * every request carries a deduplication key: a retry after a partial success finds the row and
     * becomes a no-op instead of a second push.
     */
    private fun handOff(req: NotificationRequest): Uni<Void> {
        check(req.deduplicationKey != null) { "approval notifications must be deduplicated" }
        return Uni.createFrom().deferred { notificationConsumer.consume(objectMapper.writeValueAsString(req)) }
            // No backoff on purpose: Mutiny's backoff resubscribes on its own scheduler thread, off
            // the Vert.x context the reactive Panache session needs (#1548's failure class), so
            // every delayed attempt would fail for a reason unrelated to the original fault.
            .onFailure().retry().atMost(RETRY_ATTEMPTS)
            .onFailure().invoke { e ->
                log.errorf(
                    e,
                    "Approval notification %s for party %s failed after retries — rethrowing so it is nacked",
                    req.template,
                    req.partyId,
                )
            }
    }

    private fun requestsFor(type: String, template: NotificationTemplate, e: ApprovalEvent): List<NotificationRequest> {
        val recipients = when (type) {
            TYPE_REQUESTED -> e.recipients.filterNot { it == e.initiatorPartyId }
            else -> e.recipients + e.initiatorPartyId
        }.distinct()
        val language = e.language
        val amount = formatAmount(e.amount, e.currency, language)
        val variables = buildMap {
            put("entityName", e.entityName)
            if (template != NotificationTemplate.PAYMENT_RELEASE_FAILED) put("kind", e.kind)
            put("amountFormatted", amount)
            put("payeeName", e.payeeName)
            if (template == NotificationTemplate.APPROVAL_REQUIRED) {
                put("initiatorName", e.initiatorName)
                put("expiresAt", formatExpiry(e.expiresAt, language))
            }
            if (template == NotificationTemplate.APPROVAL_REJECTED ||
                template == NotificationTemplate.PAYMENT_RELEASE_FAILED
            ) {
                put("reason", e.reason)
            }
        }.filterValues { it.isNotBlank() }
        return recipients.map { partyId ->
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = template,
                recipient = partyId.toString(),
                variables = variables,
                correlationId = e.approvalId,
                deduplicationKey = deduplicationKey(e.eventId, partyId),
                deepLink = MobileDeepLink.businessApproval(e.approvalId),
                language = language,
            )
        }
    }

    private fun parse(node: JsonNode): ApprovalEvent? {
        val eventId = node.uuid("eventId") ?: return rejectMissing("eventId")
        val approvalId = node.uuid("approvalId") ?: return rejectMissing("approvalId")
        val initiator = node.uuid("initiatorPartyId") ?: return rejectMissing("initiatorPartyId")
        val recipients = node.uuidList("recipientPartyIds") ?: return rejectMissing("recipientPartyIds")
        return ApprovalEvent(
            eventId = eventId,
            approvalId = approvalId,
            initiatorPartyId = initiator,
            recipients = recipients,
            entityName = node.text("entityName"),
            kind = node.text("kind"),
            amount = node.path("amount").takeIf { it.isNumber || it.isTextual }?.asText()
                ?.let { runCatching { BigDecimal(it) }.getOrNull() },
            currency = node.text("currency"),
            payeeName = node.text("payeeName"),
            initiatorName = node.text("initiatorName"),
            expiresAt = node.text("expiresAt").let { runCatching { Instant.parse(it) }.getOrNull() },
            reason = node.text("reason"),
            language = languageOf(node.text("locale")),
        )
    }

    /** Logs the key NAME only: the payload carries names and amounts, which do not belong in a log. */
    private fun rejectMissing(field: String): ApprovalEvent? {
        log.warnf("Dropping approval event with missing/unparseable %s", field)
        return null
    }

    private fun count(type: String, disposition: String, amount: Double = 1.0) {
        Counter.builder(METRIC_EVENTS)
            .description("Approval events read from the topic, by what this consumer did with them")
            .tag("type", type)
            .tag("disposition", disposition)
            .register(meterRegistry)
            .increment(amount)
    }

    private data class ApprovalEvent(
        val eventId: UUID,
        val approvalId: UUID,
        val initiatorPartyId: UUID,
        val recipients: List<UUID>,
        val entityName: String,
        val kind: String,
        val amount: BigDecimal?,
        val currency: String,
        val payeeName: String,
        val initiatorName: String,
        val expiresAt: Instant?,
        val reason: String,
        val language: NotificationLanguage,
    )

    companion object {
        /**
         * Counts records by disposition. `handed_off` is incremented by the number of
         * notification requests passed to the pipeline — a hand-off, not a delivery: whether a
         * provider then ACCEPTED the push is `PushMetricsPort`'s fan-out metric, and whether a
         * device displayed it is not observable server-side at all.
         */
        const val METRIC_EVENTS = "openbank.notification.approval.events"
        const val DISPOSITION_HANDED_OFF = "handed_off"
        const val DISPOSITION_IGNORED = "ignored"
        const val DISPOSITION_MALFORMED = "malformed"

        private const val TYPE_REQUESTED = "APPROVAL_REQUESTED"
        private const val RETRY_ATTEMPTS = 2L
        private val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")
        private val EXPIRY_CS = DateTimeFormatter.ofPattern("d. M. yyyy H:mm", Locale.forLanguageTag("cs"))
        private val EXPIRY_EN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

        val TEMPLATE_BY_TYPE: Map<String, NotificationTemplate> = mapOf(
            TYPE_REQUESTED to NotificationTemplate.APPROVAL_REQUIRED,
            "APPROVAL_COMPLETED" to NotificationTemplate.APPROVAL_COMPLETED,
            "APPROVAL_REJECTED" to NotificationTemplate.APPROVAL_REJECTED,
            "APPROVAL_EXPIRED" to NotificationTemplate.APPROVAL_EXPIRED,
            "PAYMENT_RELEASE_FAILED" to NotificationTemplate.PAYMENT_RELEASE_FAILED,
        )

        /** Produced, known, and deliberately silent (see class KDoc). Bounded metric tag values. */
        private val KNOWN_UNNOTIFIED_TYPES = setOf("APPROVAL_SIGNED", "PAYMENT_RELEASED")

        /** Deterministic per (event, recipient): the whole idempotency contract of this consumer. */
        fun deduplicationKey(eventId: UUID, partyId: UUID): UUID =
            UUID.nameUUIDFromBytes("delegation.approval-events:v1:$eventId:$partyId".toByteArray(Charsets.UTF_8))

        /** `1 234,50 CZK` (cs) / `1,234.50 CZK` (en); blank when the event carries no amount. */
        fun formatAmount(amount: BigDecimal?, currency: String, language: NotificationLanguage): String {
            if (amount == null) return ""
            val locale = if (language == NotificationLanguage.CS) Locale.forLanguageTag("cs-CZ") else Locale.ENGLISH
            val format = DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(locale))
            return listOf(format.format(amount), currency).filter { it.isNotBlank() }.joinToString(" ")
        }

        fun formatExpiry(at: Instant?, language: NotificationLanguage): String {
            if (at == null) return ""
            val formatter = if (language == NotificationLanguage.CS) EXPIRY_CS else EXPIRY_EN
            return formatter.format(at.atZone(PRAGUE))
        }

        private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

        private fun JsonNode.uuid(field: String): UUID? = path(field).asText("").toUuidOrNull()

        /** Every element a UUID, or `null` — one bad id rejects the list rather than silently shrinking it. */
        private fun JsonNode.uuidList(field: String): List<UUID>? {
            val array = path(field).takeIf { it.isArray } ?: return null
            val ids = array.map { it.asText("").toUuidOrNull() }
            return if (ids.any { it == null }) null else ids.filterNotNull()
        }

        /** `en*` renders English; anything else, including absent, renders Czech (the app's default). */
        private fun languageOf(locale: String): NotificationLanguage =
            if (locale.lowercase().startsWith("en")) NotificationLanguage.EN else NotificationLanguage.CS

        private fun JsonNode.text(field: String): String = path(field).takeIf { it.isTextual }?.asText().orEmpty()
    }
}
