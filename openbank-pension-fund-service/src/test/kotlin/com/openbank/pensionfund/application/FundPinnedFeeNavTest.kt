// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.application

import com.openbank.pensionfund.FixedFundTerms
import com.openbank.pensionfund.application.port.PensionFundMetrics
import com.openbank.pensionfund.application.usecase.FundAdministrationService
import com.openbank.pensionfund.application.usecase.FundDefinition
import com.openbank.pensionfund.domain.model.NavCalculator
import com.openbank.pensionfund.domain.model.NavInput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** ADR-0341: a created fund's fee is the pinned revision's, NAV accrues exactly it, and the API cannot edit it. */
class FundPinnedFeeNavTest {
    private val clock = Clock.fixed(Instant.parse("2027-01-04T00:00:00Z"), ZoneOffset.UTC)
    private val store = InMemoryStore()
    private val admin = FundAdministrationService(store, clock, 30, PensionFundMetrics.NONE, FixedFundTerms())

    private fun definition(revision: UUID?) = FundDefinition(
        name = "Pinned fee fund", isin = "CZ0000000011", lei = "5493001KJTIIGC8Y1R12",
        depositaryReference = "DEP-1", custodyAccountReference = "CUS-1", currency = "CZK", riskClass = 3,
        mandatoryConservative = false, productRevisionId = revision, launchNavPerUnit = BigDecimal.ONE,
    )

    @Test
    fun `NAV management fee equals the pinned revision value`(): Unit = kotlinx.coroutines.runBlocking {
        val fund = admin.createFund(definition(FixedFundTerms.FEE_0_80_PERCENT))

        assertThat(fund.productRevisionId).isEqualTo(FixedFundTerms.FEE_0_80_PERCENT)
        assertThat(fund.managementFeeRate).isEqualByComparingTo("0.008")
        val figures = NavCalculator.calculate(
            NavInput(
                positions = emptyList<com.openbank.pensionfund.domain.model.PricedPosition>(),
                cash = BigDecimal("365000"),
                otherLiabilities = BigDecimal.ZERO,
                unitsOutstanding = BigDecimal("1000"),
                managementFeeRate = fund.managementFeeRate,
                accrualDays = 1,
                launchNavPerUnit = fund.launchNavPerUnit,
            ),
        )
        // 365000 gross * 0.008 * 1 day / 365 = 8.00 accrued fee.
        assertThat(figures.accruedManagementFee).isEqualByComparingTo("8.00")
    }

    @Test
    fun `a fund without a resolvable published revision is refused and nothing is stored`() =
        kotlinx.coroutines.runBlocking {
            assertThatThrownBy { kotlinx.coroutines.runBlocking { admin.createFund(definition(null)) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { kotlinx.coroutines.runBlocking { admin.createFund(definition(UUID.randomUUID())) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(admin.funds()).isEmpty()
        }

    @Test
    fun `amending cannot change the fee nor re-pin the revision`(): Unit = kotlinx.coroutines.runBlocking {
        val fund = admin.createFund(definition(FixedFundTerms.FEE_0_80_PERCENT))

        val amended = admin.amendFund(fund.id, definition(null).copy(name = "Renamed"))
        assertThat(amended.managementFeeRate).isEqualByComparingTo("0.008")
        assertThat(amended.productRevisionId).isEqualTo(FixedFundTerms.FEE_0_80_PERCENT)
        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                admin.amendFund(fund.id, definition(FixedFundTerms.FEE_1_00_PERCENT))
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
