// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fx.application.port.`in`

import com.openbank.fx.domain.model.*
import java.math.BigDecimal; import java.time.Instant; import java.util.UUID

data class GetRateQuery(val baseCurrency: String, val quoteCurrency: String, val rateType: RateType = RateType.SPOT)
data class ConvertCommand(val idempotencyKey: String, val partyId: UUID, val accountId: UUID?,
    val fromCurrency: String, val toCurrency: String, val fromAmountMinorUnits: Long,
    val partyName: String)

data class GetRateHistoryQuery(
    val baseCurrency: String,
    val quoteCurrency: String,
    val source: RateSource? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Int = 100,
    val offset: Int = 0,
)

interface FxUseCase {
    suspend fun getRate(query: GetRateQuery): FxRate?
    suspend fun getAllRates(): List<FxRate>
    suspend fun getRateHistory(query: GetRateHistoryQuery): List<FxRate>
    suspend fun convert(cmd: ConvertCommand): FxConversion
    suspend fun getConversion(id: UUID): FxConversion?
    suspend fun listConversions(partyId: UUID): List<FxConversion>
}
