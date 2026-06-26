// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.application.port.out

import com.openbank.libs.persistence.outbox.OutboxRepository

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface TransactionOutboxRepository : OutboxRepository {

    /**
     * Persist an outbox row within the caller's active transaction so the outbox row and the
     * aggregate change land atomically.
     */
    suspend fun persistInTransaction(message: com.openbank.libs.persistence.outbox.OutboxMessage)
}
