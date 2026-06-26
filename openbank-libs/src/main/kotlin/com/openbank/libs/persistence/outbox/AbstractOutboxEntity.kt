// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.persistence.outbox

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.Instant
import java.util.UUID

/**
 * Plain `@MappedSuperclass` carrying the outbox columns for a **repository-pattern** entity
 * (one that does not extend Panache's active-record base).
 *
 * In practice every service uses the active-record style, so this had zero usages and services
 * hand-copied its columns instead (ADR-0049 audit). Prefer [PanacheOutboxEntity], which folds the
 * same columns into the `PanacheEntity` base services already extend. Kept only for a future
 * repository-pattern entity; remove once it is clear none will appear.
 */
@Deprecated(
    message = "Use PanacheOutboxEntity (active-record). This mapped-superclass cannot combine with " +
        "PanacheEntity under Kotlin single inheritance and has no usages.",
    replaceWith = ReplaceWith("PanacheOutboxEntity"),
)
@MappedSuperclass
abstract class AbstractOutboxEntity {
    @Column(name = "event_id", nullable = false, unique = true)
    lateinit var eventId: UUID

    @Column(name = "aggregate_id", nullable = false)
    lateinit var aggregateId: UUID

    @Column(name = "event_type", nullable = false)
    lateinit var eventType: String

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    lateinit var payload: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    fun toEntry(): OutboxEntry = OutboxEntry(
        eventId = eventId,
        aggregateId = aggregateId,
        eventType = eventType,
        payload = payload,
        status = OutboxStatus.valueOf(status),
        attemptCount = attemptCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sentAt = sentAt,
        lastError = lastError,
    )
}
