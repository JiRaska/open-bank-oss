// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository

// Re-export libs canonical types under the service alias so callers in application layer
// do not need to import openbank-libs directly from domain/usecase code.
typealias SwiftOutboxMessage = OutboxMessage

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface SwiftOutboxRepository : OutboxRepository {
    /**
     * Write a new outbox row inside the caller's active transaction (same DB connection).
     * The common lifecycle methods (listProcessable, markSent, markFailed, countProcessable)
     * are inherited from [OutboxRepository].
     */
    suspend fun persistInTransaction(message: SwiftOutboxMessage)
}
