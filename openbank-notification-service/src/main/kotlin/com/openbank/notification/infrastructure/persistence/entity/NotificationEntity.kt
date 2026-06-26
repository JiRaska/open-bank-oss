// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}
