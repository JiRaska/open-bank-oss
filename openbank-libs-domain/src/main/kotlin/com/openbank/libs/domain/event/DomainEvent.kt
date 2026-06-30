// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.event

import java.time.Instant
import java.util.UUID

abstract class DomainEvent(open val occurredAt: Instant) {
    val eventId: UUID = UUID.randomUUID()
    abstract val aggregateId: UUID
    abstract val aggregateType: String
    abstract val eventType: String
    abstract val version: Long
}
