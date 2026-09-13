// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.infrastructure.persistence.entity.GeoPrecision
import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import com.openbank.transaction.infrastructure.persistence.entity.MerchantLocationEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantCatalogRepository
import com.openbank.transaction.infrastructure.persistence.repository.MerchantLocationRepository
import com.openbank.transaction.infrastructure.persistence.repository.PanacheTransactionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * What a coordinate is allowed to claim.
 *
 * The seeded catalogue pinned each BRAND at one Prague address, so a Billa purchase in Brno resolved
 * 185 km from where it happened — on a map captioned "where you spent". Nothing was broken: the data
 * answered exactly the question it was asked, and the question was the wrong one. These tests hold
 * the two halves of the fix — the town from the descriptor picks the row, and a pin that is only
 * representative says so.
 */
class MerchantGeoResolutionTest {

    private lateinit var useCase: TransactionUseCase
    private lateinit var repository: PanacheTransactionRepository
    private lateinit var catalog: MerchantCatalogRepository
    private lateinit var locations: MerchantLocationRepository
    private lateinit var resource: TransactionResource

    private val accountId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        useCase = mockk()
        repository = mockk()
        catalog = mockk()
        locations = mockk()
        resource = TransactionResource(useCase, repository, catalog, locations)
    }

    private fun transaction(description: String) = Transaction(
        id = UUID.randomUUID(),
        referenceNumber = "REF-1",
        type = TransactionType.DEBIT,
        sourceAccountId = accountId,
        targetAccountId = null,
        amount = Money(BigDecimal("249.00"), CurrencyCode.CZK),
        fxRate = null,
        baseAmount = Money(BigDecimal("249.00"), CurrencyCode.CZK),
        status = TransactionStatus.COMPLETED,
        description = description,
        valueDate = LocalDate.parse("2026-09-07"),
        bookingDate = LocalDate.parse("2026-09-07"),
        initiatedAt = Instant.parse("2026-09-07T10:00:00Z"),
        completedAt = Instant.parse("2026-09-07T10:00:01Z"),
        failedAt = null,
        failureReason = null,
        idempotencyKey = "idem-1",
        version = 1,
    )

    /** The chain pin V16 seeded: one Prague coordinate for every Billa in the country. */
    private fun billa() = MerchantCatalogEntity().also {
        it.descriptorKey = "BILLA"
        it.cleanName = "Billa"
        it.lat = 50.0834
        it.lon = 14.4238
        it.city = "Praha"
        it.country = "CZ"
        it.geoPrecision = GeoPrecision.CITY
    }

    private fun brnoLocation() = MerchantLocationEntity().also {
        it.descriptorKey = "BILLA"
        it.cityToken = "BRNO"
        it.lat = 49.1951
        it.lon = 16.6068
        it.city = "Brno"
        it.country = "CZ"
        it.geoPrecision = GeoPrecision.CITY
    }

    private suspend fun listWith(
        description: String,
        locationRows: Map<String, MerchantLocationEntity>,
    ): MerchantResponse? {
        coEvery { useCase.listTransactions(any()) } returns
            CursorPage(listOf(transaction(description)), PageInfo(limit = 20, hasNextPage = false))
        coEvery { catalog.findByDescriptors(any()) } returns mapOf("BILLA" to billa())
        coEvery { locations.findByKeys(any()) } returns locationRows

        val response = resource.listTransactions(accountId, 20, null)

        @Suppress("UNCHECKED_CAST")
        val page = response.entity as CursorPage<TransactionResponse>
        return page.data.single().merchant
    }

    /**
     * The load-bearing case. Same merchant, different town — and the town is the token the lookup
     * key throws away, so without this the response is the Prague pin for a Brno purchase.
     */
    @Test
    fun `a purchase in Brno resolves to the Brno location, not the chain's Prague pin`() {
        val merchant = runBlocking { listWith("BILLA BRNO", mapOf("BILLA|BRNO" to brnoLocation())) }

        assertThat(merchant?.geo?.lat).isEqualTo(49.1951)
        assertThat(merchant?.geo?.city).isEqualTo("Brno")
    }

    /**
     * With no per-town row the catalogue pin is still returned — it is the best answer available and
     * the town on it is true — but it is labelled CITY, so a client captions a town instead of
     * dropping a pin claiming a street.
     */
    @Test
    fun `with no per-town row the catalogue pin is returned, labelled CITY`() {
        val merchant = runBlocking { listWith("BILLA BRNO", emptyMap()) }

        assertThat(merchant?.geo?.lat).isEqualTo(50.0834)
        assertThat(merchant?.geo?.precision).isEqualTo(GeoPrecision.CITY)
    }

    /**
     * A location row for the WRONG town must not be used. Matching on the descriptor alone would
     * hand this purchase whichever row came back first — the chain-pin bug one level down, and the
     * failure this whole design exists to prevent.
     */
    @Test
    fun `a location for another town is never substituted`() {
        val merchant = runBlocking { listWith("BILLA PLZEN", mapOf("BILLA|BRNO" to brnoLocation())) }

        assertThat(merchant?.geo?.city).isEqualTo("Praha")
        assertThat(merchant?.geo?.lat).isEqualTo(50.0834)
    }

    /** A descriptor with no town has nothing to narrow to, and must not ask for `BILLA|`. */
    @Test
    fun `a descriptor naming no town asks for no location`() {
        val merchant = runBlocking { listWith("BILLA", mapOf("BILLA|BRNO" to brnoLocation())) }

        assertThat(merchant?.geo?.city).isEqualTo("Praha")
    }

    /**
     * The only shape that may claim EXACT: a location tied to the device that took the payment.
     * The response must carry that through, because a client is allowed to draw a real pin for
     * EXACT and only a town caption for CITY — the distinction is worthless if it stops at the
     * database.
     */
    @Test
    fun `a device-resolved location surfaces as EXACT`() {
        val exact = MerchantLocationEntity().also {
            it.descriptorKey = "BILLA"
            it.cityToken = "BRNO"
            it.lat = 49.1951
            it.lon = 16.6068
            it.city = "Brno"
            it.country = "CZ"
            it.geoPrecision = GeoPrecision.EXACT
            it.terminalId = "T-00042"
        }

        val merchant = runBlocking { listWith("BILLA BRNO", mapOf("BILLA|BRNO" to exact)) }

        assertThat(merchant?.geo?.precision).isEqualTo(GeoPrecision.EXACT)
    }

    /**
     * A merchant with no coordinates anywhere carries no geo at all. Absent is the honest answer for
     * an e-shop — there is no place where the money was spent — and it must not degrade into a pin
     * at latitude 0.
     */
    @Test
    fun `a merchant with no coordinates carries no geo`() {
        val eshop = MerchantCatalogEntity().also {
            it.descriptorKey = "BILLA"
            it.cleanName = "Billa"
            it.geoPrecision = GeoPrecision.CITY
        }
        coEvery { useCase.listTransactions(any()) } returns
            CursorPage(listOf(transaction("BILLA BRNO")), PageInfo(limit = 20, hasNextPage = false))
        coEvery { catalog.findByDescriptors(any()) } returns mapOf("BILLA" to eshop)
        coEvery { locations.findByKeys(any()) } returns emptyMap()

        val response = runBlocking { resource.listTransactions(accountId, 20, null) }

        @Suppress("UNCHECKED_CAST")
        val page = response.entity as CursorPage<TransactionResponse>
        val merchant = page.data.single().merchant
        assertThat(merchant?.cleanName).isEqualTo("Billa")
        assertThat(merchant?.geo).isNull()
    }
}
