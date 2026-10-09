// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application

import com.openbank.pensionfund.application.port.MarketPricePort
import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.application.usecase.NavService
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PublishedFundBalanceTest {
    private val date = LocalDate.parse("2026-09-30")
    private val fundId = UUID.randomUUID()
    private val store = InMemoryStore()
    private val service = NavService(
        store,
        object : MarketPricePort {
            override suspend fun price(instrumentId: String, valuationDate: LocalDate, currency: String): BigDecimal? =
                null
        },
        Clock.systemUTC(),
    )

    private fun nav(status: NavStatus, valuationDate: LocalDate = date) = NavRecord(
        id = UUID.randomUUID(),
        fundId = fundId,
        valuationDate = valuationDate,
        figures = NavFigures(
            grossAssets = BigDecimal("249065.00"),
            accruedManagementFee = BigDecimal("5.46"),
            otherLiabilities = BigDecimal("1200.00"),
            netAssets = BigDecimal("247859.54"),
            unitsOutstanding = BigDecimal("200000"),
            navPerUnit = BigDecimal("1.239298"),
        ),
        status = status,
        calculatedBy = "maker",
        calculatedAt = Instant.parse("2026-09-30T16:00:00Z"),
        approvedBy = if (status == NavStatus.PUBLISHED) "checker" else null,
        publishedAt = if (status == NavStatus.PUBLISHED) Instant.parse("2026-09-30T17:00:00Z") else null,
    )

    @Test
    fun `published exact-date NAV supplies balanced source figures`(): Unit = runBlocking {
        val source = nav(NavStatus.PUBLISHED)
        store.navs[source.id] = source
        val balance = service.publishedBalance(fundId, date)
        assertThat(balance.sourceNavId).isEqualTo(source.id)
        assertThat(balance.totalAssets).isEqualByComparingTo("249065.00")
        assertThat(balance.totalLiabilities).isEqualByComparingTo("1205.46")
        assertThat(balance.totalEquity).isEqualByComparingTo("247859.54")
    }

    @Test
    fun `unpublished superseded and wrong-date NAVs cannot supply a balance`(): Unit = runBlocking {
        listOf(NavStatus.CALCULATED, NavStatus.REJECTED, NavStatus.SUPERSEDED).forEach { status ->
            val source = nav(status)
            store.navs[source.id] = source
        }
        val later = nav(NavStatus.PUBLISHED, date.plusDays(1))
        store.navs[later.id] = later
        assertThatThrownBy { runBlocking { service.publishedBalance(fundId, date) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `period maximum uses only approved current NAVs and identifies its source`(): Unit = runBlocking {
        val low = nav(NavStatus.PUBLISHED, date.minusDays(2))
            .let { it.copy(figures = it.figures.copy(navPerUnit = BigDecimal("1.20"))) }
        val supersededHigh = nav(NavStatus.SUPERSEDED, date.minusDays(1))
            .let { it.copy(figures = it.figures.copy(navPerUnit = BigDecimal("5.00"))) }
        val unpublishedHigh = nav(NavStatus.CALCULATED, date)
            .let { it.copy(figures = it.figures.copy(navPerUnit = BigDecimal("6.00"))) }
        val high = nav(NavStatus.PUBLISHED, date)
            .let { it.copy(figures = it.figures.copy(navPerUnit = BigDecimal("1.30"))) }
        val outside = nav(NavStatus.PUBLISHED, date.plusDays(1))
            .let { it.copy(figures = it.figures.copy(navPerUnit = BigDecimal("7.00"))) }
        listOf(low, supersededHigh, unpublishedHigh, high, outside).forEach { store.navs[it.id] = it }

        val maximum = service.publishedNavMaximum(fundId, date.minusDays(2), date)
        assertThat(maximum.maximumNavPerUnit).isEqualByComparingTo("1.30")
        assertThat(maximum.maximumValuationDate).isEqualTo(date)
        assertThat(maximum.sourceNavId).isEqualTo(high.id)
        assertThat(maximum.observedValuations).isEqualTo(2)
    }

    @Test
    fun `period maximum refuses inverted or unobserved periods`(): Unit = runBlocking {
        store.navs[UUID.randomUUID()] = nav(NavStatus.CALCULATED)
        assertThatThrownBy { runBlocking { service.publishedNavMaximum(fundId, date, date.minusDays(1)) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { service.publishedNavMaximum(fundId, date, date) } }
            .isInstanceOf(NotFoundException::class.java)
    }
}
