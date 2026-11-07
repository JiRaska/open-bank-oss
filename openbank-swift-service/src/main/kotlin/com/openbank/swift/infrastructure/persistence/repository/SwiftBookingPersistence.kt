// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.swift.application.port.out.SwiftOutboxMessage
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.infrastructure.persistence.entity.SwiftMessageEntity
import com.openbank.swift.infrastructure.persistence.mapper.applyUpdate
import com.openbank.swift.infrastructure.persistence.mapper.toDomain
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.persistence.LockModeType
import java.util.UUID

/** Short local transaction only; never hold the aggregate lock across a provider call. */
internal suspend fun PanacheRepository<SwiftMessageEntity>.writeBooking(
    message: SwiftMessage,
    outbox: SwiftOutboxMessage,
    provenTransactionId: UUID?,
    outboxRepository: SwiftOutboxRepositoryImpl,
    objectMapper: ObjectMapper,
): SwiftMessage = Panache.withTransaction {
    validateBookingOutbox(message, outbox, objectMapper)
    find("id", message.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { entity ->
        if (entity == null) throw ResourceConflictException("SWIFT booking message is missing")
        val current = entity.toDomain()
        validateBookingTarget(current, message, provenTransactionId)
        val sameRequest = sameBookingRequest(current, message, provenTransactionId)
        if (isBookingReplay(entity, current, message, provenTransactionId, sameRequest)) {
            return@flatMap Uni.createFrom().item(current)
        }
        validateBookingRevision(current, message, provenTransactionId, sameRequest)
        entity.applyBookingProof(message, provenTransactionId)
        entity.applyUpdate(message)
        Panache.getSession().flatMap { session ->
            session.flush().flatMap { session.refresh(entity) }.flatMap {
                outboxRepository.persistWithinCurrentTransaction(outbox).map { entity.toDomain() }
            }
        }
    }
}.awaitSuspending()

private fun validateBookingOutbox(message: SwiftMessage, outbox: SwiftOutboxMessage, objectMapper: ObjectMapper) {
    val payload = objectMapper.readTree(outbox.payload)
    val envelopeMatches = outbox.aggregateId == message.id && outbox.eventType == "swift.message.status-changed"
    val payloadMatches = payload.path("swiftMessageId").asText() == message.id.toString() &&
        payload.path("sourceService").asText() == "swift-service" &&
        payload.path("status").asText() == message.status.name
    if (!envelopeMatches || !payloadMatches) {
        throw ResourceConflictException("SWIFT booking outbox identity does not match its transition")
    }
}
