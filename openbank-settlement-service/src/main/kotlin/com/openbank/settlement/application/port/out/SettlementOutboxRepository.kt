// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

interface SettlementOutboxRepository : OutboxRepository {

    suspend fun oldestUnsentAt(): java.time.Instant?

    /** Caller owns the aggregate transaction; a failure must roll back both writes. */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
