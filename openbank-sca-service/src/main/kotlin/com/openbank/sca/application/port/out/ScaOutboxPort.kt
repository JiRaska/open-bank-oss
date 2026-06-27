// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("MatchingDeclarationName")

package com.openbank.sca.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface ScaOutboxRepository : OutboxRepository {

    /** Persist [message] in its own transaction so the dispatcher can relay it to Kafka. */
    suspend fun save(message: OutboxMessage)
}
