// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.application.port.out

import com.openbank.swift.domain.model.SwiftMessage
import java.util.UUID

/** Internal durable booking boundary; no financial retry or operator API. */
interface SwiftBookingWrites {
    suspend fun acceptForBooking(message: SwiftMessage, outbox: SwiftOutboxMessage): SwiftMessage
    suspend fun completeBooking(message: SwiftMessage, transactionId: UUID, outbox: SwiftOutboxMessage): SwiftMessage
}
