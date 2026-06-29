// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.notification.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni
import java.time.Instant

/** Outbox port for notifications: libs [OutboxRepository] + a reactive in-transaction write. */
interface NotificationOutboxRepository : OutboxRepository {
    /** Persist a new outbox row inside an already-active Panache transaction. */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>

    /**
     * Purge terminal DEAD rows whose [updatedAt] is before [threshold].
     * Used by the nightly dead-letter janitor to prevent unbounded table growth (ADR-0050 N5).
     * Returns the count of deleted rows.
     */
    fun purgeDeadBefore(threshold: Instant): Uni<Long>
}
