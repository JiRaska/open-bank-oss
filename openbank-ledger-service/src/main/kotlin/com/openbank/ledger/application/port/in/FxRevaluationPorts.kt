// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.`in`

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Run the daily FX revaluation for [date] (the business day being revalued). */
data class RevalueFxCommand(val date: LocalDate)

/**
 * Outcome of a revaluation run: whether an entry was [posted] (false when nothing moved), the
 * [journalId] of the posted entry, and the signed CZK [movements] booked per currency (gain > 0,
 * loss < 0). Currencies with a zero movement are omitted.
 */
data class FxRevaluationResult(
    val date: LocalDate,
    val posted: Boolean,
    val journalId: UUID?,
    val movements: Map<String, BigDecimal>,
)

/** Inbound port for the daily mark-to-ČNB revaluation of foreign FX positions (ADR-0046). */
interface FxRevaluationUseCase {
    suspend fun revalue(command: RevalueFxCommand): FxRevaluationResult
}
