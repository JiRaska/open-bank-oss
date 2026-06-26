// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.audit.domain.model

import java.time.Instant
import java.util.UUID

data class AuditEntry(
    val id: UUID,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val actorId: String?,
    val actorType: String?,
    val payload: String,
    val sourceService: String,
    val correlationId: String?,
    val occurredAt: Instant,
    val recordedAt: Instant
)
