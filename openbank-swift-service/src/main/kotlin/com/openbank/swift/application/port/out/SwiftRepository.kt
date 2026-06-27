// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.port.out

import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftStatus
import java.util.UUID

/** Outbound persistence port for the SWIFT message aggregate. */
interface SwiftRepository {

    suspend fun save(msg: SwiftMessage): SwiftMessage

    /** Atomically persist [msg] and write [outbox] in the same transaction (transactional-outbox pattern). */
    suspend fun saveWithOutbox(msg: SwiftMessage, outbox: SwiftOutboxMessage): SwiftMessage

    suspend fun findById(id: UUID): SwiftMessage?

    suspend fun findByIdempotencyKey(key: String): SwiftMessage?

    suspend fun listAllMessages(): List<SwiftMessage>

    suspend fun findByStatus(status: SwiftStatus): List<SwiftMessage>
}
