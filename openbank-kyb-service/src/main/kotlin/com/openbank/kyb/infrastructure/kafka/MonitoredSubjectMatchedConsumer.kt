// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.application.port.out.KybMonitoringMetricsPort
import com.openbank.kyb.application.usecase.MonitoringFindingService
import com.openbank.kyb.application.usecase.MonitoringSettings
import com.openbank.kyb.domain.model.MonitoringMatch
import com.openbank.kyb.infrastructure.MicrometerKybMonitoringMetrics
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Consumes `MONITORED_SUBJECT_MATCHED` from `openbank.sanctions.screening.event`: one finding of
 * ongoing monitoring about a subject some service registered (ADR-0376 D6). For a subject THIS
 * service registered, the business case it belongs to goes to review ([MonitoringFindingService]).
 *
 * **The event type is the `ce-type` Kafka HEADER, never a body field.** The topic is
 * outbox-relayed: sanctions-service keeps the type in the outbox row's `event_type` column and
 * its publisher sends the payload verbatim, with the type on the header
 * ([OutboxKafkaHeaders.HEADER_EVENT_TYPE]). The body is a closed schema with no `eventType`
 * (`openbank-contracts/openbank-sanctions-service/asyncapi.yaml`), so a consumer that dispatches
 * on the body matches nothing and acks every real record in silence. Hence [ConsumerRecord]
 * rather than a bare `String`, and a counter per outcome.
 *
 * What becomes of a record, each counted under its own `outcome`:
 *  - a sibling type that legitimately shares the topic — `ignored`; its body is never parsed,
 *    because a screening event carries the screened name and this consumer has no business with it;
 *  - a missing or unknown type — `unrecognised`, logged at warn. This is what a producer/consumer
 *    disagreement about the wire shape looks like, and nothing else on the channel shows it;
 *  - a finding owned by another service — `foreign_owner`. The owner is compared with the client
 *    this service registers as; a finding addressed to anyone else is not acted on, even if its
 *    reference happens to exist here;
 *  - a finding that cannot be read — `malformed`. Acked: replaying it fails identically forever;
 *  - a kyb-owned finding — whatever [MonitoringFindingService] answered.
 *
 * An infrastructure failure while applying a finding is retried with [EventRetry] and then
 * rethrown; the record is nacked and the channel's configured `failure-strategy` decides what
 * follows.
 *
 * Not gated on `openbank.kyb.monitoring.enabled`. That switch stops this service from REGISTERING;
 * a finding about a subject it registered earlier is still a finding, and dropping it because the
 * switch was later turned off would be a match nobody reviews.
 */
@ApplicationScoped
class MonitoredSubjectMatchedConsumer {

    @Inject lateinit var findings: MonitoringFindingService

    @Inject lateinit var settings: MonitoringSettings

    @Inject lateinit var metrics: KybMonitoringMetricsPort

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var clock: Clock

    private val log = Logger.getLogger(MonitoredSubjectMatchedConsumer::class.java)

    @Incoming(CHANNEL)
    suspend fun consume(record: ConsumerRecord<String, String>) {
        // The type comes off the header BEFORE the body is touched.
        when (val type = header(record, OutboxKafkaHeaders.HEADER_EVENT_TYPE)) {
            EVENT_MATCHED -> decode(record)?.let { handle(it) }
            in SIBLING_TYPES -> metrics.findingHandled(MicrometerKybMonitoringMetrics.OUTCOME_IGNORED)
            else -> {
                // Only the type is logged — never the body, which on this topic can be a screening.
                log.warnf(
                    "[%s] unrecognised or missing %s header (%.64s) at %s-%d@%d, acking",
                    CHANNEL,
                    OutboxKafkaHeaders.HEADER_EVENT_TYPE,
                    type ?: "<absent>",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                )
                metrics.findingHandled(MicrometerKybMonitoringMetrics.OUTCOME_UNRECOGNISED)
            }
        }
    }

    private suspend fun handle(node: JsonNode) {
        if (node.path("ownerService").asText("") != settings.ownerClientId) {
            metrics.findingHandled(MicrometerKybMonitoringMetrics.OUTCOME_FOREIGN_OWNER)
            return
        }
        val findingId = runCatching { UUID.fromString(node.path("findingId").asText()) }.getOrNull()
        val subjectRef = node.path("subjectRef").asText("")
        if (findingId == null || subjectRef.isBlank()) {
            // The body of a finding names no person, so a prefix of it is safe to log.
            log.errorf("[%s] finding without a usable findingId or subjectRef, acking: %.200s", CHANNEL, node)
            metrics.findingHandled(MicrometerKybMonitoringMetrics.OUTCOME_MALFORMED)
            return
        }
        val match = MonitoringMatch(
            findingId = findingId,
            matchKind = node.path("matchKind").asText(UNKNOWN),
            listType = node.path("listType").asText(UNKNOWN),
            listEntryRef = node.path("listEntryRef").asText(UNKNOWN),
            listEntryVersion = node.path("listEntryVersion").asText(UNKNOWN),
            matchScore = node.path("matchScore").asDouble(0.0),
            matchStatus = node.path("matchStatus").asText(UNKNOWN),
            detection = node.path("detection").asText(UNKNOWN),
            receivedAt = Instant.now(clock),
        )
        val outcome = EventRetry.withRetry(log, "[$CHANNEL] monitoring finding", findingId) {
            findings.handle(subjectRef, match)
        }
        metrics.findingHandled(outcome.tag)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decode(record: ConsumerRecord<String, String>): JsonNode? = try {
        objectMapper.readTree(record.value()).takeIf { it.isObject } ?: malformed(null)
    } catch (e: Exception) {
        malformed(e)
    }

    private fun malformed(cause: Exception?): JsonNode? {
        log.errorf(cause, "[%s] undecodable finding body, acking", CHANNEL)
        metrics.findingHandled(MicrometerKybMonitoringMetrics.OUTCOME_MALFORMED)
        return null
    }

    companion object {
        const val CHANNEL = "sanctions-monitoring-in"
        const val EVENT_MATCHED = "MONITORED_SUBJECT_MATCHED"
        private const val UNKNOWN = "UNKNOWN"

        /** Every other type sanctions-service publishes on the same topic. */
        private val SIBLING_TYPES =
            setOf("SanctionChecked", "SanctionReviewed", "SANCTIONS_LIST_CHANGED", "SANCTIONS_LIST_CHANGE_STORM")

        private fun header(record: ConsumerRecord<String, String>, name: String): String? =
            record.headers().lastHeader(name)?.value()?.let { String(it, StandardCharsets.UTF_8) }
    }
}
