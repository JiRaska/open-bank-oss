// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.pricing

import com.openbank.pensionfund.application.port.MarketPricePort
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Stub market data: knows NO prices. A NAV calculation therefore needs every position's price in
 * the request until a deployment binds [MarketPricePort] to a real vendor adapter — an unpriced
 * position is refused rather than valued at an invented number, because a NAV built on a made-up
 * price is a wrong price paid to every participant who trades at it.
 */
@ApplicationScoped
class StubMarketPriceAdapter : MarketPricePort {
    override suspend fun price(instrumentId: String, valuationDate: LocalDate, currency: String): BigDecimal? = null
}
