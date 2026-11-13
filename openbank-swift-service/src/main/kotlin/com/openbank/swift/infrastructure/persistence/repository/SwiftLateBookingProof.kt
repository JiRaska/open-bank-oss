// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.infrastructure.persistence.repository

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftStatus
import com.openbank.swift.infrastructure.persistence.entity.SwiftMessageEntity
import com.openbank.swift.infrastructure.persistence.mapper.toDomain
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

/** No network I/O or business transition: retain a validated answer to the original attempted request. */
internal suspend fun PanacheRepository<SwiftMessageEntity>.writeLateBookingProof(
    attempt: SwiftMessage,
    transactionId: UUID,
    provenAt: Instant,
): SwiftMessage = Panache.withTransaction {
    find("id", attempt.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { entity ->
        if (entity == null) throw ResourceConflictException("SWIFT late booking message is missing")
        val current = entity.toDomain()
        validateLateBookingRequest(entity, current, attempt)
        if (entity.bookingTransactionId == transactionId && entity.bookingProvenAt != null) {
            return@flatMap Uni.createFrom().item(current)
        }
        if (entity.bookingTransactionId != null || entity.bookingProvenAt != null) {
            throw ResourceConflictException("SWIFT booking proof is immutable")
        }
        // Deliberately do not applyUpdate: ACK/REJECT, timestamps and operator metadata stay intact.
        entity.bookingTransactionId = transactionId
        entity.bookingProvenAt = provenAt
        entity.bookingClockCovered = true
        Panache.getSession().flatMap { session ->
            session.flush().flatMap { session.refresh(entity) }.map { entity.toDomain() }
        }
    }
}.awaitSuspending()

private fun validateLateBookingRequest(entity: SwiftMessageEntity, current: SwiftMessage, attempt: SwiftMessage) {
    val acceptedAttempt = attempt.messageType == SwiftMessageType.MT103 &&
        attempt.status == SwiftStatus.SENT &&
        attempt.orderingCustomerAccountId != null
    val operatorDecision = current.status in setOf(SwiftStatus.ACKNOWLEDGED, SwiftStatus.REJECTED)
    val durableStop = entity.bookingFirstDueAt != null && entity.bookingRedriveStoppedAt != null
    if (!acceptedAttempt || !operatorDecision || !durableStop) {
        throw ResourceConflictException("SWIFT late booking request or operator stop does not match")
    }
    if (!sameLateBookingRequest(current, attempt)) {
        throw ResourceConflictException("SWIFT late booking request or operator stop does not match")
    }
}

/** All request/identity fields, including value date, stay exact; only operator decision fields may differ. */
private fun sameLateBookingRequest(current: SwiftMessage, attempt: SwiftMessage): Boolean = current.copy(
    status = attempt.status,
    ackReceivedAt = attempt.ackReceivedAt,
    rejectionReason = attempt.rejectionReason,
    updatedAt = attempt.updatedAt,
    version = attempt.version,
) == attempt
