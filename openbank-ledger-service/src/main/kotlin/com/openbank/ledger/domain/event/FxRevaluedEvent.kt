// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.event

import com.openbank.libs.domain.event.DomainEvent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Emitted when the daily FX revaluation posts an entry (ADR-0046, #1201 proposed fix 3).
 * [movements] is the signed CZK unrealized P&L booked per currency for [date] (gain > 0, loss <
 * 0). Enqueued in the SAME outbox transaction as the revaluation's own `JournalPosted`
 * (`PostJournalCommand.additionalOutboxMessages` — see `FxRevaluationService.revalue`), so it
 * inherits the same at-least-once + dedup delivery guarantee as every other ledger event, instead
 * of the previous direct post-commit `LedgerEventPublisher` call: that publish happened AFTER the
 * journal's own transaction had already committed, so a crash in the gap between the two silently
 * lost this event — the "losable on crash" half of #1201 finding L-12. There is no longer a
 * dedicated `openbank.ledger.fx.revalued` topic: like every other ledger domain event, this rides
 * the shared `ledger-events-out` channel, distinguished by the `ce-type` header (ADR-0050 N3).
 */
data class FxRevaluedEvent(
    override val aggregateId: UUID,
    val date: LocalDate,
    val movements: Map<String, BigDecimal>,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "FxRevaluation"
    override val eventType = "FxRevalued"
    override val version = 0L
}
