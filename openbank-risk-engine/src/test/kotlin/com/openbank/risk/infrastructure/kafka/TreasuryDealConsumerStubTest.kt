// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A booked deal with the default `NONE` stub is valued, and its stored legs carry the resolved stub. */
class TreasuryDealConsumerStubTest {
    private val consumer = TreasuryDealConsumer().also {
        it.objectMapper = ObjectMapper()
        it.registry = SimpleMeterRegistry()
    }

    private fun payload(stub: String?, roll: String?) = """
        {"dealId":"00000000-0000-0000-0000-000000000001","product":"IRS","counterpartyId":"c","currency":"CZK",
         "principal":1000000,"rate":3,"valueDate":"2026-01-10","maturityDate":"2027-03-10"
         ${roll?.let { ""","rollDirection":"$it"""" } ?: ""},
         "legs":[{"payReceive":"PAY","currency":"CZK","notional":1000000,"accrualStart":"2026-01-10",
           "accrualEnd":"2027-03-10","accrualFrequency":"3M"${stub?.let { ""","stub":"$it"""" } ?: ""},
           "rate":{"type":"FIXED","rate":0.03}}]}
    """.trimIndent()

    private fun stored(stub: String?, roll: String?): String {
        val event = consumer.parse("treasury.deal.booked.v1", payload(stub, roll))!!
        assertThat(event.legsError).isNull()
        return ObjectMapper().readTree(event.legsJson).get(0).get("stub").asText()
    }

    @Test
    fun `NONE and an absent stub resolve by the published roll direction`() {
        assertThat(stored("NONE", "FROM_START")).isEqualTo("SHORT_FINAL")
        assertThat(stored(null, "FROM_START")).isEqualTo("SHORT_FINAL")
        assertThat(stored("NONE", "FROM_END")).isEqualTo("SHORT_INITIAL")
    }

    @Test
    fun `an event without a roll direction is FROM_END, treasury's default`() {
        assertThat(stored("NONE", null)).isEqualTo("SHORT_INITIAL")
    }

    @Test
    fun `an explicit stub is kept and an unknown stub is unvaluable`() {
        assertThat(stored("LONG_FINAL", "FROM_END")).isEqualTo("LONG_FINAL")
        val bad = consumer.parse("treasury.deal.booked.v1", payload("SIDEWAYS", "FROM_END"))!!
        assertThat(bad.legsError).contains("stub")
    }
}
