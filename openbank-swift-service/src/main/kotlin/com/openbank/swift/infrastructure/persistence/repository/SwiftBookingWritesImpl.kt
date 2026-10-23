// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.swift.application.port.out.SwiftBookingWrites
import com.openbank.swift.application.port.out.SwiftOutboxMessage
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.infrastructure.persistence.entity.SwiftMessageEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class SwiftBookingWritesImpl(private val outbox: SwiftOutboxRepositoryImpl, private val objectMapper: ObjectMapper) :
    SwiftBookingWrites,
    PanacheRepository<SwiftMessageEntity> {
    override suspend fun acceptForBooking(message: SwiftMessage, outbox: SwiftOutboxMessage): SwiftMessage =
        writeBooking(message, outbox, null, this.outbox, objectMapper)

    override suspend fun completeBooking(
        message: SwiftMessage,
        transactionId: UUID,
        outbox: SwiftOutboxMessage,
    ): SwiftMessage = writeBooking(message, outbox, transactionId, this.outbox, objectMapper)
}
