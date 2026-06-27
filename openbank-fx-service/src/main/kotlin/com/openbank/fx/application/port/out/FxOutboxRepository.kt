// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

/** Outbox port for fx: libs [OutboxRepository] + a reactive in-transaction write. */
interface FxOutboxRepository : OutboxRepository {
    /** Persist a new outbox row inside an already-active Panache transaction. */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
