// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.DisclosureSnapshotUseCase
import com.openbank.document.application.port.`in`.IssueDisclosureSnapshotCommand
import com.openbank.libs.messaging.EventRetry
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger
import java.util.UUID

/**
 * mTLS/ACL-authenticated command boundary for disclosure snapshot issuance. No REST write endpoint
 * exists: the Kafka principal is unique to delegation-service, unlike the fleet's shared OIDC
 * client. Retrying is safe because requestId deterministically identifies both row and blob.
 */
@ApplicationScoped
class DisclosureSnapshotRequestedConsumer(
    private val snapshots: DisclosureSnapshotUseCase,
    private val objectMapper: ObjectMapper,
    @Channel("disclosure-snapshot-events-out") private val emitter: MutinyEmitter<String>,
) {
    private val log = Logger.getLogger(DisclosureSnapshotRequestedConsumer::class.java)

    @Incoming("delegation-disclosure-commands-in")
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.warnf("Unparseable disclosure snapshot command, skipping: %s", payload.take(PAYLOAD_LOG_CHARS))
            return
        }
        if (node.path("eventType").asText() != REQUESTED) return
        val requestId = node.uuid("requestId") ?: return emitRejected(null, "INVALID_COMMAND")
        val sourceId = node.uuid("sourceDocumentId") ?: return emitRejected(requestId, "INVALID_COMMAND")
        val partyRef = node.path("expectedPartyRef").asText().takeIf { it.isNotBlank() }
            ?: return emitRejected(requestId, "INVALID_COMMAND")

        try {
            EventRetry.withRetry(
                log,
                "Disclosure snapshot $requestId",
                null,
                isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
            ) {
                val snapshot = snapshots.issue(IssueDisclosureSnapshotCommand(requestId, sourceId, partyRef))
                    ?: return@withRetry emitRejected(requestId, "SOURCE_NOT_FOUND")
                emit(
                    requestId,
                    DisclosureSnapshotReady(
                        requestId,
                        snapshot.id,
                        snapshot.sourceDocumentId,
                        snapshot.sourceSha256,
                        snapshot.sha256,
                        snapshot.sizeBytes,
                        snapshot.createdAt,
                    ),
                )
            }
        } catch (e: IllegalArgumentException) {
            emitRejected(requestId, "SOURCE_NOT_ELIGIBLE")
        }
    }

    private suspend fun emitRejected(requestId: UUID?, reason: String) {
        emit(requestId, DisclosureSnapshotRejected(requestId, reason))
    }

    private suspend fun emit(requestId: UUID?, body: Any) {
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(requestId?.toString() ?: INVALID_PARTITION_KEY)
            .build()
        emitter.sendMessage(Message.of(objectMapper.writeValueAsString(body)).addMetadata(metadata)).awaitSuspending()
    }

    private fun com.fasterxml.jackson.databind.JsonNode.uuid(field: String): UUID? =
        path(field).asText().let { runCatching { UUID.fromString(it) }.getOrNull() }

    private companion object {
        const val REQUESTED = "DisclosureSnapshotRequested"
        const val INVALID_PARTITION_KEY = "invalid-command"
        const val PAYLOAD_LOG_CHARS = 200
    }
}

data class DisclosureSnapshotReady(
    val requestId: UUID,
    val snapshotId: UUID,
    val sourceDocumentId: UUID,
    val sourceSha256: String,
    val sha256: String,
    val sizeBytes: Long,
    val occurredAt: java.time.Instant,
    val eventType: String = EVENT_TYPE,
) {
    companion object {
        const val EVENT_TYPE = "DisclosureSnapshotReady"
    }
}

data class DisclosureSnapshotRejected(val requestId: UUID?, val reason: String, val eventType: String = EVENT_TYPE) {
    companion object {
        const val EVENT_TYPE = "DisclosureSnapshotRejected"
    }
}
