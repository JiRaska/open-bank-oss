// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.infrastructure.catalog

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.infrastructure.client.CatalogOfferingClientResponse
import com.openbank.interest.infrastructure.client.CatalogRevisionClientResponse
import com.openbank.interest.infrastructure.client.CatalogRevisionContentClientResponse
import com.openbank.interest.infrastructure.client.CatalogSchemaRefClientResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class CatalogInterestProfileParserTest {
    private val revisionId = UUID.fromString("11000000-0000-0000-0000-000000000001")
    private val offeringId = UUID.fromString("11000000-0000-0000-0000-000000000002")
    private val specificationId = UUID.fromString("11000000-0000-0000-0000-000000000003")

    @Test
    fun `maps a fixed midnight-aligned deposit revision without binary conversion`() {
        val profile = CatalogInterestProfileParser.parse(
            revision(
                """{"currency":"CZK","interest":{"rateType":"FIXED","annualRate":"0.123456789012345678","dayCount":"ACT_365"}}""",
            ),
            offering(),
        )

        assertThat(profile.specificationId).isEqualTo(specificationId)
        assertThat(profile.currency).isEqualTo("CZK")
        assertThat(profile.annualRate).isEqualByComparingTo(BigDecimal("0.123456789012345678"))
        assertThat(profile.dayCount).isEqualTo(DayCount.ACT_365)
        assertThat(profile.effectiveFrom).hasToString("2026-08-20")
        assertThat(profile.effectiveTo).hasToString("2026-08-31")
    }

    @Test
    fun `rejects tiered interest rather than inventing its calculation semantics`() {
        assertThatThrownBy {
            CatalogInterestProfileParser.parse(
                revision(
                    """{"currency":"CZK","interest":{"rateType":"TIERED","tiers":[{"fromBalance":"0","annualRate":"0.01"}],"dayCount":"ACT_365"}}""",
                ),
                offering(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("only FIXED is executable")
    }

    @Test
    fun `rejects a partial-day catalog interval before it can affect a daily accrual`() {
        assertThatThrownBy {
            CatalogInterestProfileParser.parse(
                revision(
                    """{"currency":"CZK","interest":{"rateType":"FIXED","annualRate":"0.01","dayCount":"ACT_365"}}""",
                    effectiveFrom = OffsetDateTime.parse("2026-08-20T12:00:00Z"),
                ),
                offering(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("midnight UTC")
    }

    private fun offering() = CatalogOfferingClientResponse(offeringId, specificationId)

    private fun revision(
        attributes: String,
        effectiveFrom: OffsetDateTime = OffsetDateTime.parse("2026-08-20T00:00:00Z"),
    ) = CatalogRevisionClientResponse(
        id = revisionId,
        offeringId = offeringId,
        schemaRef = CatalogSchemaRefClientResponse("org.openbank.banking.deposit", 2),
        state = "PUBLISHED",
        content = CatalogRevisionContentClientResponse(jacksonObjectMapper().readTree(attributes)),
        effectiveFrom = effectiveFrom,
        effectiveTo = OffsetDateTime.parse("2026-09-01T00:00:00Z"),
        contentHash = "a".repeat(64),
    )
}
