// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.event

import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Emitted on every accounting-day transition (ADR-0207 D4), via the transactional outbox in the
 * SAME transaction as the state change — either the day moves and the event is queued, or neither.
 *
 * **Published, not polled.** Consumers that need to know whether a day is closed react to this
 * rather than querying ledger-service on every posting: a synchronous day-state lookup from every
 * money-path service into ledger-service on the hot path would make ledger-service a hard
 * availability dependency of all of them — a worse failure than the one being fixed.
 *
 * A NEW event type on the existing ledger event stream, therefore additive and backward
 * compatible. [aggregateId] is the AccountingDayRecord id.
 */
data class AccountingDayTransitionedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    override val occurredAt: Instant,
    val businessDate: LocalDate,
    val fromStatus: String,
    val toStatus: String,
    val transitionedBy: String,
) : DomainEvent(occurredAt) {
    override val aggregateType = "AccountingDay"
    override val eventType = "AccountingDayTransitioned"
}
