// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DisclosureRepository
import com.openbank.delegation.domain.model.DisclosureStatus
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class DisclosureSnapshotEventConsumer(
    private val disclosures: DisclosureRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(javaClass)

    @Incoming("disclosure-snapshot-events-in")
    suspend fun consume(payload: String) {
        val node = objectMapper.readTree(payload)
        val eventType = node.path("eventType").asText()
        if (eventType !in EVENT_TYPES) return
        val requestId = UUID.fromString(node.required("requestId").asText())
        val occurredAt = Instant.parse(node.required("occurredAt").asText())
        EventRetry.withRetry(log, "Disclosure snapshot outcome $requestId", null) {
            when (eventType) {
                READY -> disclosures.markReady(
                    requestId,
                    UUID.fromString(node.required("snapshotId").asText()),
                    UUID.fromString(node.required("sourceDocumentId").asText()),
                    node.required("sourceSha256").asText(),
                    node.required("sha256").asText(),
                    node.required("sizeBytes").asLong(),
                    occurredAt,
                )
                REJECTED -> disclosures.markRejected(requestId, node.required("reason").asText(), occurredAt)
            }
            val saved = disclosures.findByRequestId(requestId)
                ?: error("snapshot outcome arrived before disclosure request")
            if (eventType == READY) {
                check(saved.status == DisclosureStatus.READY) { "snapshot-ready outcome did not match its request" }
                check(saved.snapshotId == UUID.fromString(node.required("snapshotId").asText())) {
                    "snapshot outcome conflicts with the recorded immutable result"
                }
                check(saved.sourceDocumentId == UUID.fromString(node.required("sourceDocumentId").asText())) {
                    "snapshot outcome source conflicts with its disclosure request"
                }
                check(saved.sourceSha256 == node.required("sourceSha256").asText()) {
                    "snapshot outcome source digest conflicts with the recorded immutable result"
                }
                check(saved.snapshotSha256 == node.required("sha256").asText()) {
                    "snapshot outcome digest conflicts with the recorded immutable result"
                }
                check(saved.sizeBytes == node.required("sizeBytes").asLong()) {
                    "snapshot outcome size conflicts with the recorded immutable result"
                }
            } else {
                check(saved.status == DisclosureStatus.REJECTED) {
                    "snapshot-rejected outcome did not match its request"
                }
                check(saved.rejectionReason == node.required("reason").asText()) {
                    "snapshot rejection conflicts with the recorded terminal result"
                }
            }
        }
    }

    private companion object {
        const val READY = "DisclosureSnapshotReady"
        const val REJECTED = "DisclosureSnapshotRejected"
        val EVENT_TYPES = setOf(READY, REJECTED)
    }
}
