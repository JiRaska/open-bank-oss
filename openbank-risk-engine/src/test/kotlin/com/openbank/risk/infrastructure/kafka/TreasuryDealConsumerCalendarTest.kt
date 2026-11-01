// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.kafka

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.domain.calendar.BusinessDayConvention
import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.leg.DateRules
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.NotionalSpec
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.leg.ScheduleSpec
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.ScheduleGenerator
import com.openbank.libs.finance.schedule.StubType
import com.openbank.risk.infrastructure.finance.LegBounds
import com.openbank.risk.infrastructure.finance.LegDefaults
import com.openbank.risk.infrastructure.finance.LegDto
import com.openbank.risk.infrastructure.finance.LegSpecJson
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The deal header's `calendars` and `businessDayConvention` on a booked event drive the schedule the
 * risk engine derives, so it equals the one treasury generates for the same legs through libs-finance.
 */
class TreasuryDealConsumerCalendarTest {
    private val mapper = ObjectMapper()
    private val consumer = TreasuryDealConsumer().also {
        it.objectMapper = mapper
        it.registry = SimpleMeterRegistry()
    }

    private fun payload(header: String) = """
        {"dealId":"00000000-0000-0000-0000-000000000002","product":"IRS","counterpartyId":"c","currency":"CZK",
         "principal":1000000,"rate":3,"valueDate":"2026-10-28","maturityDate":"2027-10-28",
         "rollDirection":"FROM_START"$header,
         "legs":[{"payReceive":"PAY","currency":"CZK","notional":1000000,"accrualStart":"2026-10-28",
           "accrualEnd":"2027-10-28","accrualFrequency":"3M","rate":{"type":"FIXED","rate":0.03}}]}
    """.trimIndent()

    /** The schedule the risk engine derives from the stored legs of a consumed event. */
    private fun derived(header: String): List<LocalDate> {
        val event = consumer.parse("treasury.deal.booked.v1", payload(header))!!
        assertThat(event.legsError).isNull()
        val dtos = mapper.readValue(event.legsJson, object : TypeReference<List<LegDto>>() {})
        val leg = LegSpecJson.toLegs(dtos, LegDefaults(), LegBounds.BOOKED).single()
        return ScheduleGenerator.generate(leg).periods.map { it.paymentDate }
    }

    /** Treasury's side: the same leg built straight from libs-finance on the joint calendar. */
    private fun treasuryLeg(roll: BusinessDayConvention) = LegSpec(
        payReceive = PayReceive.PAY,
        currency = CurrencyCode.of("CZK"),
        notional = NotionalSpec(BigDecimal("1000000"), finalExchange = true),
        schedule = ScheduleSpec(
            start = LocalDate.parse("2026-10-28"),
            end = LocalDate.parse("2027-10-28"),
            accrualFrequency = Frequency.Months(3),
            paymentFrequency = Frequency.Months(3),
            stub = StubType.resolve(null, com.openbank.libs.finance.schedule.RollDirection.FROM_START),
            endOfMonth = false,
        ),
        dates = DateRules(
            calendar = Calendars.joint(listOf(BusinessCalendar.certis(), BusinessCalendar.target2())),
            roll = roll,
            adjustAccrualPeriod = true,
            adjustPaymentDate = true,
            paymentLagBusinessDays = 0,
        ),
        rate = RateSpec.Fixed(BigDecimal("0.03")),
        dayCount = DayCount.ACT_360,
    )

    @Test
    fun `a FROM_START deal on CERTIS+TARGET2 with MODIFIED_FOLLOWING derives treasury's schedule`() {
        val header = ""","calendars":["CERTIS","TARGET2"],"businessDayConvention":"MODIFIED_FOLLOWING""""
        val expected = ScheduleGenerator.generate(treasuryLeg(BusinessDayConvention.MODIFIED_FOLLOWING))
            .periods.map { it.paymentDate }

        assertThat(derived(header)).isEqualTo(expected)
        // 2026-10-28 is a Czech holiday: the convention moved the first date off the unadjusted one.
        assertThat(expected).doesNotContain(LocalDate.parse("2026-10-28"))
    }

    @Test
    fun `the published convention is applied, and an absent one keeps the default`() {
        val preceding = derived(""","calendars":["CERTIS","TARGET2"],"businessDayConvention":"PRECEDING"""")
        val modified = derived(""","calendars":["CERTIS","TARGET2"],"businessDayConvention":"MODIFIED_FOLLOWING"""")
        assertThat(preceding).isNotEqualTo(modified)
        // Absent calendars/convention: today's default, which is not the deal's PRECEDING.
        assertThat(derived("")).isNotEqualTo(preceding)
    }
}
