// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sanctions.domain.model

import java.time.Instant
import java.util.UUID

data class SanctionsList(
    val id: UUID,
    val listType: String,
    val displayName: String,
    val sourceUrl: String,
    val enabled: Boolean,
    val lastUpdatedAt: Instant?,
    val lastEntryCount: Int?,
    val cronHour: Int,
    val cronMinute: Int,
    val cronDays: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class UpdateSanctionsListRequest(
    val enabled: Boolean?,
    val sourceUrl: String?,
    val cronHour: Int?,
    val cronMinute: Int?,
    val cronDays: String?,
)
