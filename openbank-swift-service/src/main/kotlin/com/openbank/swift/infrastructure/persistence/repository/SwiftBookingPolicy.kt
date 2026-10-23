// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.infrastructure.persistence.repository

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftStatus
import com.openbank.swift.infrastructure.persistence.entity.SwiftMessageEntity
import java.util.UUID

internal fun validateBookingTarget(current: SwiftMessage, message: SwiftMessage, id: UUID?) {
    val target = if (id == null) SwiftStatus.SENT else SwiftStatus.COMPLETED
    if (message.status != target || current.messageType != SwiftMessageType.MT103) {
        throw ResourceConflictException("SWIFT booking transition is invalid")
    }
}

internal fun sameBookingRequest(current: SwiftMessage, message: SwiftMessage, id: UUID?): Boolean = current.copy(
    status = message.status,
    rawMt = if (id == null) message.rawMt else current.rawMt,
    updatedAt = message.updatedAt,
    version = message.version,
) == message

internal fun isBookingReplay(
    entity: SwiftMessageEntity,
    current: SwiftMessage,
    message: SwiftMessage,
    id: UUID?,
    sameRequest: Boolean,
): Boolean {
    if (!sameRequest) return false
    if (id != null) return entity.bookingTransactionId == id && entity.bookingProvenAt != null
    val accepted = current.status == SwiftStatus.SENT && entity.bookingFirstDueAt != null
    val pending = entity.bookingProvenAt == null && current.rawMt == message.rawMt
    return accepted && pending
}

internal fun validateBookingRevision(current: SwiftMessage, message: SwiftMessage, id: UUID?, sameRequest: Boolean) {
    val expected = if (id == null) SwiftStatus.VALIDATED else SwiftStatus.SENT
    val revisionMatches = current.version == message.version && current.status == expected
    if (!revisionMatches || !sameRequest) throw ResourceConflictException("SWIFT booking revision or request changed")
}

internal fun SwiftMessageEntity.applyBookingProof(message: SwiftMessage, id: UUID?) {
    if (bookingProvenAt != null || bookingTransactionId != null) {
        throw ResourceConflictException("SWIFT booking proof is immutable")
    }
    if (id == null) {
        bookingFirstDueAt = bookingFirstDueAt ?: message.updatedAt
    } else {
        bookingProvenAt = message.updatedAt
        bookingTransactionId = id
    }
    bookingClockCovered = true
}

internal fun isUntrackedBookingWrite(entity: SwiftMessageEntity, message: SwiftMessage): Boolean {
    val untracked = entity.bookingFirstDueAt == null && entity.bookingProvenAt == null
    return untracked && message.status in setOf(SwiftStatus.SENT, SwiftStatus.COMPLETED, SwiftStatus.ACKNOWLEDGED)
}
