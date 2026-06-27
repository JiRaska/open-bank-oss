// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.aml.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface AmlOutboxRepository : OutboxRepository {

    /** Persist an outbox row within the caller's active transaction. */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
