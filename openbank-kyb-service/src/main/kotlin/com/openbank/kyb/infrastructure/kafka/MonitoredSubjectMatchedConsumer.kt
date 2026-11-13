// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.application.port.out.KybMonitoringMetricsPort
import com.openbank.kyb.application.usecase.MonitoringFindingService
import com.openbank.kyb.application.usecase.MonitoringSettings
import com.openbank.kyb.application.usecase.ReportedFinding
import com.openbank.kyb.infrastructure.MicrometerKybMonitoringMetrics
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Consumes `MONITORED_SUBJECT_MATCHED` from `openbank.sanctions.screening.event`: one finding of
 * ongoing monitoring about a subject some service registered (ADR-0376 D6). For a subject THIS
 * service registered, the finding is recorded on its business case ([MonitoringFindingService]).
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
 *  - a record typed as a finding without a usable owner, finding id or subject reference —
 *    `malformed`, and NACKED so the channel dead-letters it. Checked before the owner: a body the
 *    producer changed must not read as somebody else's finding;
 *  - a well-formed finding owned by another service — `foreign_owner`. The owner is compared with
 *    the client this service registers as; a finding addressed to anyone else is not acted on,
 *    even if its reference happens to exist here;
 *  - a kyb-owned finding — whatever [MonitoringFindingService] answered.
 *
 * An infrastructure failure while applying a finding is retried with [EventRetry] and then
 * rethrown; like a malformed finding, the record is nacked and the channel's configured
 * `failure-strategy` decides what follows.
 *
 * The channel itself exists only while `openbank.kyb.monitoring.enabled` is true (application.yaml
 * binds the channel's `enabled` to it). Switched off, the pod opens no consumer for this topic at
 * all, so it needs neither the topic's ACL nor the dead-letter topic to start, and a deploy does
 * not depend on those having been applied first. Findings published meanwhile wait on the topic
 * for the consumer group and are read when the switch is turned on again.
 */
/** A record typed as a monitoring finding that cannot be one. Carries nothing of the record. */
class MalformedFindingException : RuntimeException("malformed MONITORED_SUBJECT_MATCHED record")

@ApplicationScoped
class MonitoredSubjectMatchedConsumer {

    @Inject lateinit var findings: MonitoringFindingService

    @Inject lateinit var settings: MonitoringSettings

    @Inject lateinit var metrics: KybMonitoringMetricsPort

    @Inject lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(MonitoredSubjectMatchedConsumer::class.java)

    @Incoming(CHANNEL)
    suspend fun consume(record: ConsumerRecord<String, String>) {
        // The type comes off the header BEFORE the body is touched.
        when (val type = header(record, OutboxKafkaHeaders.HEADER_EVENT_TYPE)) {
            EVENT_MATCHED -> handle(record)
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

    /**
     * Shape first, owner second. A record typed as a finding that has no usable owner, finding id
     * or subject reference is NOT somebody else's finding: it is a producer that changed its body,
     * and if it were counted as foreign every finding of this service would be acked in silence.
     */
    private suspend fun handle(record: ConsumerRecord<String, String>) {
        val finding = parse(record) ?: run {
            // Position and type only: never the body.
            log.errorf(
                "[%s] a %s record without a usable ownerService, findingId or subjectRef at %s-%d@%d",
                CHANNEL,
                EVENT_MATCHED,
                record.topic(),
                record.partition(),
                record.offset(),
            )
            metrics.findingHandled(MicrometerKybMonitoringMetrics.OUTCOME_MALFORMED)
            // Nacked, not acked: the channel dead-letters it, where it can be read and replayed.
            throw MalformedFindingException()
        }
        if (finding.owner != settings.ownerClientId) {
            metrics.findingHandled(MicrometerKybMonitoringMetrics.OUTCOME_FOREIGN_OWNER)
            return
        }
        val outcome = EventRetry.withRetry(log, "[$CHANNEL] monitoring finding", finding.match.findingId) {
            findings.handle(finding.subjectRef, finding.match)
        }
        metrics.findingHandled(outcome.tag)
    }

    private class Finding(val owner: String, val subjectRef: String, val match: ReportedFinding)

    @Suppress("ReturnCount") // one early return per missing required part
    private fun parse(record: ConsumerRecord<String, String>): Finding? {
        // Why it could not be read is not interesting, and its text is never logged: null means malformed.
        val node = runCatching { objectMapper.readTree(record.value()) }.getOrNull()?.takeIf { it.isObject }
            ?: return null
        val owner = node.path("ownerService").asText("").takeIf { it.isNotBlank() } ?: return null
        val subjectRef = node.path("subjectRef").asText("").takeIf { it.isNotBlank() } ?: return null
        val findingId = runCatching { UUID.fromString(node.path("findingId").asText()) }.getOrNull() ?: return null
        return Finding(
            owner,
            subjectRef,
            ReportedFinding(
                findingId = findingId,
                matchKind = node.path("matchKind").asText(UNKNOWN),
                listType = node.path("listType").asText(UNKNOWN),
                listEntryRef = node.path("listEntryRef").asText(UNKNOWN),
                listEntryVersion = node.path("listEntryVersion").asText(UNKNOWN),
                matchScore = node.path("matchScore").asDouble(0.0),
                matchStatus = node.path("matchStatus").asText(UNKNOWN),
                detection = node.path("detection").asText(UNKNOWN),
            ),
        )
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
