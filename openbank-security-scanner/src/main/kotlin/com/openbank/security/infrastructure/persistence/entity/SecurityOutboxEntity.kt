// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.security.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "security_outbox")
class SecurityOutboxEntity : PanacheEntity() {
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
}
