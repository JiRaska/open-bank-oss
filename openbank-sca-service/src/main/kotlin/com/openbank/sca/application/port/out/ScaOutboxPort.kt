// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("MatchingDeclarationName")

package com.openbank.sca.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface ScaOutboxRepository : OutboxRepository {

    /**
     * Persist [message] inside the CALLER's transaction — it opens none of its own, so the
     * aggregate write and its event commit together or not at all (#8679). The predecessor
     * `save(message)` wrapped its own `Panache.withTransaction`, which is why the enrolled
     * device landed in one transaction and `sca.device_enrolled` in the next: a crash between
     * the two commits enrolled the device and lost the event permanently.
     *
     * Mirrors `DocumentOutboxRepository`/`FxOutboxRepository`. Callers must already be inside a
     * `Panache.withTransaction { … }` block.
     */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
