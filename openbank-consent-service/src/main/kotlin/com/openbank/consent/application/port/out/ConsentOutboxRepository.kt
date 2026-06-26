// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.consent.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface ConsentOutboxRepository : OutboxRepository {

    /**
     * Persist a new outbox message in the same transaction as the aggregate state change.
     * Returns a [Uni<Void>] so callers can compose it in a reactive transaction pipeline.
     */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
