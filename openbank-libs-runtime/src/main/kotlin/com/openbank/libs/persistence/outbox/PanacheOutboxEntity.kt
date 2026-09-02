// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.Instant
import java.util.UUID

/**
 * Active-record base for a service's outbox table. A concrete entity is then just:
 *
 * ```
 * @Entity
 * @Table(name = "account_outbox")
 * class AccountOutboxEntity : PanacheOutboxEntity()
 * ```
 *
 * Why this and not [AbstractOutboxEntity]: the latter is a plain `@MappedSuperclass`, but every
 * service entity extends Quarkus' [PanacheEntity] (for the generated `id` + active-record API),
 * and Kotlin has no multiple inheritance — so [AbstractOutboxEntity] had **zero** usages and each
 * service re-declared the same ten columns by hand (ADR-0049 audit). This class folds the columns
 * into the Panache base so the column set, `@Table`-less mapping and [toEntry] live in one place.
 */
@MappedSuperclass
open class PanacheOutboxEntity : PanacheEntity() {
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

    /**
     * ADR-0252 durable hand-off: request-scoped taint cannot survive asynchronous dispatch.
     * Each concrete outbox table receives this column in the same fleet migration.
     */
    @Column(name = "synthetic", nullable = false)
    var synthetic: Boolean = false

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
        synthetic = synthetic,
    )
}
