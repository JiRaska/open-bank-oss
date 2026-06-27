// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class FxRateTest {

    @Test
    fun `isValid returns false for expired rate`() {
        val rate = fxRate(
            validFrom = Instant.parse("2026-01-01T00:00:00Z"),
            validTo = Instant.parse("2026-01-01T01:00:00Z"),
        )

        assertThat(rate.isValid(Instant.parse("2026-01-01T01:00:01Z"))).isFalse()
    }

    @Test
    fun `midRate is the average of bid and ask`() {
        val rate = fxRate(bid = "1.08", ask = "1.12")

        assertThat(rate.midRate).isEqualByComparingTo("1.10")
    }

    @Test
    fun `spread is ask minus bid`() {
        val rate = fxRate(bid = "1.08", ask = "1.12")

        assertThat(rate.spread).isEqualByComparingTo("0.04")
    }

    private fun fxRate(
        bid: String = "1.00",
        ask: String = "1.01",
        validFrom: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        validTo: Instant = Instant.parse("2026-12-31T23:59:59Z"),
    ) = FxRate(
        id = UUID.randomUUID(),
        baseCurrency = "EUR",
        quoteCurrency = "CZK",
        bidRate = BigDecimal(bid),
        askRate = BigDecimal(ask),
        rateType = RateType.SPOT,
        source = RateSource.INTERNAL,
        validFrom = validFrom,
        validTo = validTo,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
