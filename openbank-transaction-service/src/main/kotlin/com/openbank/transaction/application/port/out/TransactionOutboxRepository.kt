// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.port.out

import com.openbank.libs.persistence.outbox.ClaimFencedOutboxRepositoryV2

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface TransactionOutboxRepository : ClaimFencedOutboxRepositoryV2 {

    /**
     * Persist an outbox row within the caller's active transaction so the outbox row and the
     * aggregate change land atomically.
     */
    suspend fun persistInTransaction(message: com.openbank.libs.persistence.outbox.OutboxMessage)
}
