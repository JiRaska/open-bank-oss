// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.notification.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

/** Outbox port for notifications: libs [OutboxRepository] + a reactive in-transaction write. */
interface NotificationOutboxRepository : OutboxRepository {
    /** Persist a new outbox row inside an already-active Panache transaction. */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}
