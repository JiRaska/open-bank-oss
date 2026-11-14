// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.ScreeningTriggerService
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Consumes `SANCTIONS_LIST_CHANGED` from `openbank.sanctions.screening.event` — the first
 * closed ADR-0256 trigger loop: sanctions-service's change journal (V15) publishes a diff event
 * per 64 changed/deactivated external ids; this consumer opens one screening case per candidate
 * party through the case coordinator (ADR-0256 contract), durably and idempotently.
 *
 * **The event type is the `ce-type` Kafka HEADER, never a body field.** The topic is
 * outbox-relayed: sanctions-service stores the type in the outbox row's `event_type` column and
 * its publisher sends the row's payload verbatim, moving the type onto the header
 * ([OutboxKafkaHeaders.HEADER_EVENT_TYPE]). The body schemas are closed and carry no `eventType`
 * (`openbank-contracts/openbank-sanctions-service/asyncapi.yaml`). Dispatching on
 * `node.path("eventType")` therefore matched no branch for every real record and acked it
 * silently — hence [ConsumerRecord] rather than a bare `String`, and a counter per outcome.
 *
 * Event-type handling:
 * - `SANCTIONS_LIST_CHANGE_STORM` — the producer's storm guard (publication withheld). It must
 *   NOT open cases: the journal is retained upstream and a later publication re-fires the real
 *   diff. Logged at info and acked.
 * - `SANCTIONS_LIST_CHANGED` — handed to [ScreeningTriggerService.handleListChange].
 * - the screening/review types that legitimately share the topic — acked, counted `ignored`.
 * - a missing header or a type this consumer does not know — acked, counted `unrecognised` and
 *   logged at warn. That outcome is the one to alert on: it is what a producer/consumer
 *   disagreement about the wire shape looks like, and nothing else on the channel shows it.
 *
 * Failure handling follows the #5698 split (see PartyEventConsumer):
 * - a malformed payload is unretryable — logged and acked (the only poison-pill case);
 * - infrastructure failure (the trigger table, downstream reactive persistence) is retried with
 *   [EventRetry] and RETHROWN so the connector dead-letters — the channel wires
 *   failure-strategy dead-letter-queue with an explicit per-service topic;
 * - coordinator-level failure (429/503) is different from both: it is recorded durably
 *   (OPEN_FAILED trigger + KYC_COORDINATOR_OPEN_FAILED outbox row) and ACKED, because the retry
 *   job owns re-attempt. Rethrowing would double-book that role.
 *
 * `openbank.kyc.screening-triggers.enabled` (default false) is the ONE switch deciding whether
 * the loop opens cases — here and in [com.openbank.kyc.infrastructure.trigger.ScreeningTriggerRetryJob].
 * The ADR-0256 contract's rollout sequence deploys it off until the Pact verification, sandbox
 * coordinator exercise and operator-task metrics land. Off does NOT mean dropped: the change is
 * still recorded, one DEFERRED trigger row per candidate party (coalesced, no coordinator call,
 * counted `recorded_while_disabled`), and turning the switch on drains them (issue #15961).
 */
@ApplicationScoped
class SanctionsListChangedConsumer {

    @Inject
    lateinit var screeningTriggerService: ScreeningTriggerService

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.kyc.screening-triggers.enabled", defaultValue = "false")
    lateinit var enabled: String

    @ConfigProperty(name = "openbank.kyc.screening-triggers.candidate-limit", defaultValue = "500")
    lateinit var candidateLimit: String

    @Inject
    lateinit var meterRegistry: MeterRegistry

    private val log = Logger.getLogger(SanctionsListChangedConsumer::class.java)

    @Incoming("sanctions-list-changes-in")
    suspend fun consume(record: ConsumerRecord<String, String>) {
        // The type comes off the header BEFORE the body is touched: the screening events that
        // share this topic carry screened names, and this consumer has no business parsing them.
        when (val type = header(record, OutboxKafkaHeaders.HEADER_EVENT_TYPE)) {
            EVENT_STORM -> {
                val node = decode(record) ?: return
                // Deliberately NOT a case trigger: the guard means the diff was withheld as
                // suspicious; the retained journal republishes a real diff when it clears.
                log.infof(
                    "[sanctions-list-changes-in] SANCTIONS_LIST_CHANGE_STORM from %s (%s) — no cases opened",
                    node.path("listType").asText("?"),
                    node.path("reason").asText("?"),
                )
                count(OUTCOME_STORM)
            }
            EVENT_CHANGED -> decode(record)?.let { handleChanged(it) }
            in SIBLING_TYPES -> count(OUTCOME_IGNORED)
            else -> {
                // Only the type is logged — never the body, which on this topic can be a screening.
                log.warnf(
                    "[sanctions-list-changes-in] unrecognised or missing %s header (%.64s) at %s-%d@%d, acking",
                    OutboxKafkaHeaders.HEADER_EVENT_TYPE,
                    type ?: "<absent>",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                )
                count(OUTCOME_UNRECOGNISED)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decode(record: ConsumerRecord<String, String>): JsonNode? = try {
        objectMapper.readTree(record.value()).takeIf { it.isObject } ?: malformed(record, null)
    } catch (e: Exception) {
        malformed(record, e)
    }

    /** Malformed payload is the poison-pill case: replaying fails identically forever. Acked. */
    private fun malformed(record: ConsumerRecord<String, String>, cause: Exception?): JsonNode? {
        log.errorf(cause, "[sanctions-list-changes-in] undecodable event body, acking: %.300s", record.value())
        count(OUTCOME_MALFORMED)
        return null
    }

    private fun count(outcome: String) {
        meterRegistry.counter(METRIC_EVENTS, "outcome", outcome).increment()
    }

    private suspend fun handleChanged(node: JsonNode) {
        val publicationId = runCatching { UUID.fromString(node.path("publicationId").asText()) }.getOrNull()
        if (publicationId == null) {
            log.warnf(
                "[sanctions-list-changes-in] SANCTIONS_LIST_CHANGED without a valid publicationId, skipping: %.300s",
                node.toString(),
            )
            count(OUTCOME_MALFORMED)
            return
        }
        val change = ScreeningTriggerService.ListChange(
            publicationId = publicationId,
            chunkIndex = node.path("chunkIndex").asLong(0),
            listType = node.path("listType").asText(null),
            changedExternalIds = node.path("changedExternalIds").map { it.asText() },
            deactivatedExternalIds = node.path("deactivatedExternalIds").map { it.asText() },
        )
        if (!enabled.toBoolean()) {
            // Switch off: record, never act. Same retry-then-rethrow as the live path — losing the
            // row here is exactly the defect this branch exists to prevent.
            val recorded = EventRetry.withRetry(log, "ADR-0256 sanctions-list-changed recording", publicationId) {
                screeningTriggerService.recordListChange(change, candidateLimit.toInt())
            }
            log.infof(
                "[sanctions-list-changes-in] trigger loop OFF — publication %s chunk %d: %d candidates, %d deferred, %d coalesced",
                publicationId,
                change.chunkIndex,
                recorded.candidates,
                recorded.candidates - recorded.duplicates,
                recorded.duplicates,
            )
            count(OUTCOME_RECORDED_WHILE_DISABLED)
            return
        }
        // Retry-then-rethrow for INFRASTRUCTURE failure only; coordinator failure is durably
        // recorded inside the service and must not reach the DLQ (see class doc).
        val result = EventRetry.withRetry(log, "ADR-0256 sanctions-list-changed handling", publicationId) {
            screeningTriggerService.handleListChange(change, candidateLimit.toInt())
        }
        log.infof(
            "[sanctions-list-changes-in] publication %s chunk %d/%d: %d candidates, %d opened, %d failed, %d duplicates",
            publicationId,
            change.chunkIndex,
            node.path("chunkCount").asLong(-1),
            result.candidates,
            result.opened,
            result.failed,
            result.duplicates,
        )
        count(OUTCOME_HANDLED)
    }

    companion object {
        /** Records seen on the channel by what became of them; `unrecognised` is the alertable one. */
        const val METRIC_EVENTS = "openbank.kyc.sanctions_list_change_events"
        const val OUTCOME_HANDLED = "handled"
        const val OUTCOME_STORM = "storm"

        /** Switch off: queued as deferred triggers, no case opened yet (drained when on). */
        const val OUTCOME_RECORDED_WHILE_DISABLED = "recorded_while_disabled"
        const val OUTCOME_IGNORED = "ignored"
        const val OUTCOME_MALFORMED = "malformed"
        const val OUTCOME_UNRECOGNISED = "unrecognised"

        private const val EVENT_CHANGED = "SANCTIONS_LIST_CHANGED"
        private const val EVENT_STORM = "SANCTIONS_LIST_CHANGE_STORM"

        /** The screening and review events sanctions-service publishes on the same topic. */
        private val SIBLING_TYPES = setOf("SanctionChecked", "SanctionReviewed")

        private fun header(record: ConsumerRecord<String, String>, name: String): String? =
            record.headers().lastHeader(name)?.value()?.let { String(it, StandardCharsets.UTF_8) }
    }
}
