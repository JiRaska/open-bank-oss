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

    // --- inverting a pair (the ČNB fixing only ever publishes FOREIGN→CZK) -------------------

    @Test
    fun `inverting swaps the pair`() {
        val inverted = fxRate().inverted()

        assertThat(inverted.baseCurrency).isEqualTo("CZK")
        assertThat(inverted.quoteCurrency).isEqualTo("EUR")
    }

    @Test
    fun `inverting swaps bid and ask, not just the numbers`() {
        // EUR/CZK: the bank buys EUR at 24.00, sells it at 25.00. Inverted, the customer selling
        // CZK to buy EUR must get 1/25.00 — the ASK side — not 1/24.00.
        val inverted = fxRate(bid = "24.00", ask = "25.00").inverted()

        assertThat(inverted.bidRate).isEqualByComparingTo("0.04")
        assertThat(inverted.askRate).isEqualByComparingTo("0.04166667")
        assertThat(inverted.bidRate).isLessThan(inverted.askRate)
    }

    @Test
    fun `a naive one-over-bid would quote the wrong side`() {
        // Guards the mistake this method exists to prevent: 1/bid on both sides hands the
        // customer the bank's buying price on a sell, on every CZK→foreign exchange.
        val rate = fxRate(bid = "24.00", ask = "25.00")

        assertThat(rate.inverted().bidRate).isNotEqualByComparingTo(
            BigDecimal.ONE.divide(rate.bidRate, 8, java.math.RoundingMode.HALF_UP),
        )
    }

    @Test
    fun `inverting twice returns to the original pair and ordering`() {
        val original = fxRate(bid = "24.00", ask = "25.00")
        val round = original.inverted().inverted()

        assertThat(round.baseCurrency).isEqualTo(original.baseCurrency)
        assertThat(round.quoteCurrency).isEqualTo(original.quoteCurrency)
        assertThat(round.bidRate).isLessThan(round.askRate)
    }

    // --- identity of a derived quote (#3374) -------------------------------------------------

    @Test
    fun `the inverted quote has its own id, not the source row's`() {
        // The defect this replaces: EUR/CZK and CZK/EUR answered under ONE id with different
        // pairs, so an id no longer identified what it named.
        val original = fxRate()

        assertThat(original.inverted().id).isNotEqualTo(original.id)
    }

    @Test
    fun `the inverted quote names the row it came from`() {
        val original = fxRate()

        assertThat(original.inverted().derivedFrom).isEqualTo(original.id)
    }

    @Test
    fun `a stored quote declares no derivation`() {
        // The discriminator only works if it is absent on the other side.
        assertThat(fxRate().derivedFrom).isNull()
    }

    @Test
    fun `the derived id is deterministic across calls`() {
        // A client may cache by id; a fresh id per request would make that cache useless and the
        // id itself meaningless as a reference in an audit record.
        val original = fxRate()

        assertThat(original.inverted().id).isEqualTo(original.inverted().id)
    }

    @Test
    fun `the derived id depends on the source row`() {
        // Two different stored rows must not derive to one inverse id.
        assertThat(fxRate().inverted().id).isNotEqualTo(fxRate().inverted().id)
    }

    @Test
    fun `the derived id is a well-formed RFC 9562 version 8 UUID`() {
        val derived = fxRate().inverted().id

        assertThat(derived.version()).isEqualTo(EXPECTED_UUID_VERSION)
        assertThat(derived.variant()).isEqualTo(EXPECTED_UUID_VARIANT)
    }

    @Test
    fun `validity window is unchanged by inverting`() {
        val original = fxRate()
        val inverted = original.inverted()

        assertThat(inverted.validFrom).isEqualTo(original.validFrom)
        assertThat(inverted.validTo).isEqualTo(original.validTo)
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

    private companion object {
        const val EXPECTED_UUID_VERSION = 8
        const val EXPECTED_UUID_VARIANT = 2
    }
}
