// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.application.port.out

import java.math.BigDecimal

/** A quoted FX rate pair as seen by the transaction domain (bid/ask around base/quote). */
data class FxRateView(
    val baseCurrency: String,
    val quoteCurrency: String,
    val bidRate: BigDecimal,
    val askRate: BigDecimal,
)

/** Outbound port for fetching FX rates from fx-service. Returns null when no rate is quoted. */
interface FxRatePort {
    suspend fun getRate(baseCurrency: String, quoteCurrency: String): FxRateView?
}
