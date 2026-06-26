// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.event

import java.time.Instant
import java.util.UUID

abstract class DomainEvent {
    val eventId: UUID = UUID.randomUUID()
    val occurredAt: Instant = Instant.EPOCH
    abstract val aggregateId: UUID
    abstract val aggregateType: String
    abstract val eventType: String
    abstract val version: Long
}
