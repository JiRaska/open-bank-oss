// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.interest.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

/**
 * Outbound port for writing to the transactional outbox. Extends the shared [OutboxRepository]
 * (listProcessable / countProcessable / markSent / markFailed) from libs (ADR-0049 D3).
 */
interface InterestOutboxRepository : OutboxRepository {
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
