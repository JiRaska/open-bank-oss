// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.port.`in`

import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import java.time.Instant
import java.util.UUID

data class GetRateQuery(val baseCurrency: String, val quoteCurrency: String, val rateType: RateType = RateType.SPOT)
data class ConvertCommand(
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID?,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmountMinorUnits: Long,
    val partyName: String,
)

data class GetRateHistoryQuery(
    val baseCurrency: String,
    val quoteCurrency: String,
    val source: RateSource? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Int = 100,
    val offset: Int = 0,
)

/**
 * The outcome of resolving a pair: the [rate] to quote, and — when the pair had to be answered by
 * inverting the stored direction — [derivedFrom], the id of the stored row the quote was derived
 * from. The REST layer nulls the response `id` on a derived quote (#3374); the domain [rate] keeps
 * the source row's id either way, so `FxConversion.rateId` always names a real `fx_rates` row.
 */
data class ResolvedRate(val rate: FxRate, val derivedFrom: UUID?)

interface FxUseCase {
    suspend fun getRate(query: GetRateQuery): ResolvedRate?
    suspend fun getAllRates(): List<FxRate>
    suspend fun getRateHistory(query: GetRateHistoryQuery): List<FxRate>
    suspend fun convert(cmd: ConvertCommand): FxConversion
    suspend fun getConversion(id: UUID): FxConversion?
    suspend fun listConversions(partyId: UUID): List<FxConversion>
}
