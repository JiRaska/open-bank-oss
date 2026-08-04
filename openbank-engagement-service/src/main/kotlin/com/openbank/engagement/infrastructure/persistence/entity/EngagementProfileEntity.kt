// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "engagement_profiles")
class EngagementProfileEntity {
    @Id
    lateinit var partyId: UUID

    @Column(nullable = false)
    var enrolled: Boolean = false

    @Column(nullable = false)
    var adverseState: Boolean = false

    @Column(nullable = false)
    var streakDays: Int = 0

    var lastActivityAt: Instant? = null

    @Column(nullable = false)
    var totalPoints: Int = 0

    @Column(nullable = false)
    var earnedThisYear: Int = 0

    // JSON array of BadgeType names — text/not-jsonb (same V2 pattern as campaign-service)
    @Column(nullable = false, columnDefinition = "text")
    var badgesJson: String = "[]"

    @Column(nullable = false)
    lateinit var createdAt: Instant

    @Column(nullable = false)
    lateinit var updatedAt: Instant
}
