// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface KycOutboxRepository : OutboxRepository {

    /**
     * Persist an outbox row in the same transaction as the originating state change.
     * Returns a [Uni<Void>] so callers can compose it into a reactive transaction chain.
     */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
