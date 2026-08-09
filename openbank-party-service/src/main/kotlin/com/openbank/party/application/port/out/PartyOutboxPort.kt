// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.party.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

/** Outbound port for the transactional outbox: write, then drain (read pending, mark sent/failed). */
interface PartyOutboxRepository : OutboxRepository {

    /**
     * Writes [message] using the CALLER's reactive session, so it joins whatever
     * `Panache.withTransaction` block invoked it — that join is the whole point (issue #4007):
     * the party state change and its event either both commit or neither does. Returns a `Uni`
     * rather than suspending precisely so it can be chained inside that block.
     */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
