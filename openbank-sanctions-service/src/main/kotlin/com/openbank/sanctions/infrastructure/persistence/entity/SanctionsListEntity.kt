// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.entity

import com.openbank.sanctions.domain.model.SanctionsList
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sanctions_lists")
class SanctionsListEntity {
    @field:Id
    var id: UUID = UUID.randomUUID()

    @field:Column(name = "list_type", unique = true)
    var listType: String = ""

    @field:Column(name = "display_name")
    var displayName: String = ""

    @field:Column(name = "source_url", columnDefinition = "TEXT")
    var sourceUrl: String = ""

    @field:Column(name = "enabled")
    var enabled: Boolean = true

    @field:Column(name = "last_updated_at")
    var lastUpdatedAt: Instant? = null

    @field:Column(name = "last_entry_count")
    var lastEntryCount: Int? = null

    @field:Column(name = "cron_hour")
    var cronHour: Int = 6

    @field:Column(name = "cron_minute")
    var cronMinute: Int = 0

    @field:Column(name = "cron_days")
    var cronDays: String = "MON,TUE,WED,THU,FRI"

    @field:Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @field:Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    fun toDomain() = SanctionsList(
        id = id, listType = listType, displayName = displayName, sourceUrl = sourceUrl,
        enabled = enabled, lastUpdatedAt = lastUpdatedAt, lastEntryCount = lastEntryCount,
        cronHour = cronHour, cronMinute = cronMinute, cronDays = cronDays,
        createdAt = createdAt, updatedAt = updatedAt,
    )
}
