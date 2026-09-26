// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.model.InterestRateType
import com.openbank.interest.domain.model.RateIndex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The payload is hand-built, so these tests are what stand between it and malformed JSON. */
class InterestRateChangedTest {

    private val at = Instant.parse("2026-09-24T10:15:30Z")
    private val stamp = OffsetDateTime.of(2026, 9, 24, 10, 0, 0, 0, ZoneOffset.UTC)

    private fun config(productId: String = "SAVINGS_CZK") = InterestRateConfig(
        id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        productId = productId,
        currency = "CZK",
        rateType = InterestRateType.VARIABLE,
        annualRate = BigDecimal("0.0425"),
        rateIndex = RateIndex.PRIBOR_3M,
        spread = BigDecimal("0.0010"),
        dayCount = DayCount.ACT_365,
        effectiveFrom = LocalDate.of(2026, 10, 1),
        createdAt = stamp,
        updatedAt = stamp,
    )

    @Test
    fun `carries the whole configuration, not a diff`() {
        val msg = InterestRateChanged.outboxMessage(config(), InterestRateChanged.Change.CREATED, at)
        val json = ObjectMapper().readTree(msg.payload)

        assertThat(msg.eventType).isEqualTo("interest.rate.changed.v1")
        assertThat(msg.aggregateId).isEqualTo(config().id)
        assertThat(msg.createdAt).isEqualTo(at)
        assertThat(json["change"].asText()).isEqualTo("CREATED")
        assertThat(json["configId"].asText()).isEqualTo("11111111-2222-3333-4444-555555555555")
        assertThat(json["rateType"].asText()).isEqualTo("VARIABLE")
        // Decimal strings, never binary floats: a rate is money-adjacent.
        assertThat(json["annualRate"].isTextual).isTrue()
        assertThat(json["annualRate"].asText()).isEqualTo("0.0425")
        assertThat(json["rateIndex"].asText()).isEqualTo("PRIBOR_3M")
        assertThat(json["spread"].asText()).isEqualTo("0.0010")
        assertThat(json["effectiveFrom"].asText()).isEqualTo("2026-10-01")
        assertThat(json["effectiveTo"].isNull).isTrue()
        assertThat(json["accountId"].isNull).isTrue()
        assertThat(json["active"].asBoolean()).isTrue()
        assertThat(json["occurredAt"].asText()).isEqualTo("2026-09-24T10:15:30Z")
        assertThat(json["sourceService"].asText()).isEqualTo("interest-service")
    }

    @Test
    fun `a product id with quotes, backslashes and control characters still yields valid JSON`() {
        val nasty = "A\"B\\C\nD\u0001"
        val json = ObjectMapper().readTree(
            InterestRateChanged.outboxMessage(config(nasty), InterestRateChanged.Change.SUPERSEDED, at).payload,
        )

        assertThat(json["productId"].asText()).isEqualTo(nasty)
    }
}
