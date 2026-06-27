// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.event

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Emitted when the daily FX revaluation posts an entry (ADR-0046). [movements] is the signed CZK
 * unrealized P&L booked per currency for [date] (gain > 0, loss < 0). The authoritative, durable
 * signal remains the transactional-outbox `JournalPosted` for [journalId]; this is the
 * domain-specific notification on `openbank.ledger.fx.revalued`.
 */
data class FxRevaluedEvent(val journalId: UUID, val date: LocalDate, val movements: Map<String, BigDecimal>)
