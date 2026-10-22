// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.treasury.domain.cashflow.RateSource
import com.openbank.treasury.domain.cashflow.RateSourceWire
import com.openbank.treasury.domain.model.DayCountConvention
import com.openbank.treasury.domain.model.LegRate
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.PayReceive
import com.openbank.treasury.domain.model.StubType
import com.openbank.treasury.infrastructure.rest.LegSpecDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The libs-finance schedule types keep treasury's wire spelling (`ONCE`, `MONTHLY`, `NONE`). */
class LegWireJacksonTest {
    // As the service's mapper: Quarkus does not fail on unknown properties (a LegSpec writes `singlePeriod`).
    private val mapper = LegWireJackson.register(
        jacksonObjectMapper().registerModule(
            JavaTimeModule(),
        ).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
    )
    private val start = LocalDate.parse("2026-11-02")
    private val leg = LegSpec(
        PayReceive.PAY,
        "CZK",
        BigDecimal("1000000.00"),
        start,
        start.plusYears(1),
        DayCountConvention.ACT_360,
        LegRate.fixed(BigDecimal("4.00")),
        accrualFrequency = Frequency.QUARTERLY,
    )

    @Test
    fun `a stored leg writes frequency codes and NONE for no stub, and reads them back`() {
        val json = mapper.readTree(mapper.writeValueAsString(leg))
        assertThat(json["accrualFrequency"].asText()).isEqualTo("QUARTERLY")
        assertThat(json["paymentFrequency"].asText()).isEqualTo("ONCE")
        assertThat(json["stub"].asText()).isEqualTo("NONE")
        val back = mapper.readValue<LegSpec>(json.toString())
        assertThat(
            Triple(back.accrualFrequency, back.paymentFrequency, back.stub),
        ).isEqualTo(Triple(Frequency.QUARTERLY, Frequency.Zero, null))
        val stubbed = mapper.readValue<LegSpec>(
            mapper.writeValueAsString(leg.copy(stub = StubType.LONG_FINAL, paymentFrequency = Frequency.DAILY)),
        )
        assertThat(stubbed.stub).isEqualTo(StubType.LONG_FINAL)
        assertThat(stubbed.paymentFrequency).isEqualTo(Frequency.DAILY)
    }

    @Test
    fun `the REST form keeps the same spelling`() {
        val dto = mapper.readTree(mapper.writeValueAsString(LegSpecDto.from(leg)))
        assertThat(dto["stub"].asText()).isEqualTo("NONE")
        assertThat(dto["accrualFrequency"].asText()).isEqualTo("QUARTERLY")
        val parsed = mapper.readValue<LegSpecDto>("""{"stub":"NONE","accrualFrequency":"WEEKLY"}""")
        assertThat(parsed.stub).isNull()
        assertThat(parsed.accrualFrequency).isEqualTo(Frequency.WEEKLY)
    }

    @Test
    fun `an unknown code is a format error, never a default`() {
        assertThatThrownBy { mapper.readValue<LegSpecDto>("""{"accrualFrequency":"HOURLY"}""") }
            .isInstanceOf(InvalidFormatException::class.java)
        assertThatThrownBy { mapper.readValue<LegSpecDto>("""{"stub":"MEDIUM_FINAL"}""") }
            .isInstanceOf(InvalidFormatException::class.java)
    }

    @Test
    fun `a leg frequency without a named code is rejected`() {
        assertThatThrownBy { leg.copy(accrualFrequency = Frequency.Months(2)).validate() }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("ONCE")
    }

    @Test
    fun `a row with no rate yet is stored and published as MISSING`() {
        assertThat(RateSourceWire.encode(null)).isEqualTo("MISSING")
        assertThat(RateSourceWire.decode("MISSING")).isNull()
        RateSource.entries.forEach { assertThat(RateSourceWire.decode(RateSourceWire.encode(it))).isEqualTo(it) }
    }
}
