// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notifications")
class NotificationEntity : PanacheEntity() {
    @Column(name = "notification_id", nullable = false, unique = true)
    lateinit var notificationId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(nullable = false)
    lateinit var channel: String

    @Column(nullable = false)
    lateinit var template: String

    @Column(nullable = false)
    lateinit var recipient: String

    @Column
    var subject: String? = null

    @Column(nullable = false, columnDefinition = "TEXT")
    lateinit var body: String

    @Column(nullable = false)
    lateinit var status: String

    /**
     * The producer's own id for this request (ADR-0239 D1), echoed unchanged. Nullable: most
     * producers never set one. Persisted rather than only carried in memory so an operator can join
     * a notification row back to whatever asked for it, which is the whole forensic value.
     */
    @Column(name = "correlation_id")
    var correlationId: UUID? = null

    /** Nullable generic idempotency key; V14's partial unique index applies only when present. */
    @Column(name = "deduplication_key")
    var deduplicationKey: UUID? = null

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    // Customer read-state (V8): null = unread. Set once via PATCH /{id}/read | /read-all.
    @Column(name = "read_at")
    var readAt: Instant? = null

    // Why this row ended FAILED (V13). The value is the same NotificationOutcomeEvent.REASON_*
    // constant the outcome event carries — the event is the stream, this is the queryable record,
    // and the outbox rows behind the event are pruned after dispatch.
    @Column(name = "failure_reason", length = FAILURE_REASON_MAX_LENGTH)
    var failureReason: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object {
        const val FAILURE_REASON_MAX_LENGTH = 64
    }
}
