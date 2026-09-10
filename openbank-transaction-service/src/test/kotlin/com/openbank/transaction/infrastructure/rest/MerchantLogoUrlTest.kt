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
import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantCatalogRepository
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
 * What a customer's client is told about a merchant's logo.
 *
 * The catalogue holds TWO different URLs and only one of them may leave the bank. `logo_url` is
 * provenance — where the bitmap was obtained, typically a third-party host — and returning it here
 * would make every statement render tell that host the customer's IP address and which merchant
 * they paid. The field a client receives is derived, origin-relative, and content-hashed. These
 * tests hold that apart, because both are strings on the same object and swapping them back is a
 * one-word edit that nothing else would notice.
 */
class MerchantLogoUrlTest {

    private lateinit var useCase: TransactionUseCase
    private lateinit var repository: PanacheTransactionRepository
    private lateinit var catalog: MerchantCatalogRepository
    private lateinit var resource: TransactionResource

    private val accountId: UUID = UUID.randomUUID()
    private val hash = "0123456789abcdef" + "f".repeat(48)

    @BeforeEach
    fun setUp() {
        useCase = mockk()
        repository = mockk()
        catalog = mockk()
        resource = TransactionResource(useCase, repository, catalog)
    }

    private fun transaction() = Transaction(
        id = UUID.randomUUID(),
        referenceNumber = "REF-1",
        type = TransactionType.DEBIT,
        sourceAccountId = accountId,
        targetAccountId = null,
        amount = Money(BigDecimal("249.00"), CurrencyCode.CZK),
        fxRate = null,
        baseAmount = Money(BigDecimal("249.00"), CurrencyCode.CZK),
        status = TransactionStatus.COMPLETED,
        description = "ALZA.CZ A.S. PRAHA 4",
        valueDate = LocalDate.parse("2026-09-07"),
        bookingDate = LocalDate.parse("2026-09-07"),
        initiatedAt = Instant.parse("2026-09-07T10:00:00Z"),
        completedAt = Instant.parse("2026-09-07T10:00:01Z"),
        failedAt = null,
        failureReason = null,
        idempotencyKey = "idem-1",
        version = 1,
    )

    private fun alza(logoEtag: String?) = MerchantCatalogEntity().also {
        it.descriptorKey = "ALZACZ"
        it.cleanName = "Alza.cz"
        // Provenance: a third-party host. It must never reach a client.
        it.logoUrl = "https://logo.example.com/alza.cz.png"
        it.logoEtag = logoEtag
        it.category = "SHOPPING"
    }

    private suspend fun merchantOf(entity: MerchantCatalogEntity): MerchantResponse? {
        coEvery { useCase.listTransactions(any()) } returns
            CursorPage(listOf(transaction()), PageInfo(limit = 20, hasNextPage = false))
        coEvery { catalog.findByDescriptors(any()) } returns mapOf("ALZACZ" to entity)

        val response = resource.listTransactions(accountId, 20, null)

        @Suppress("UNCHECKED_CAST")
        val page = response.entity as CursorPage<TransactionResponse>
        return page.data.single().merchant
    }

    @Test
    fun `the logo URL points at this bank, carries the size and is versioned by the content hash`() {
        val merchant = runBlocking { merchantOf(alza(hash)) }

        assertThat(merchant?.logoUrl).isEqualTo("/api/v1/merchants/ALZACZ/logo?size=64&v=0123456789abcdef")
    }

    /**
     * The one that matters. A client that received the provenance URL would fetch the logo from a
     * third party, and the privacy property the whole design exists for would be gone — silently,
     * with the image still rendering correctly.
     */
    @Test
    fun `the provenance URL is never what a client receives`() {
        val merchant = runBlocking { merchantOf(alza(hash)) }

        assertThat(merchant?.logoUrl).doesNotContain("logo.example.com")
        assertThat(merchant?.logoUrl).startsWith("/")
    }

    /** No ingested logo means no field. Absence stays absence; there is no placeholder to send. */
    @Test
    fun `a merchant with no ingested logo carries no logo URL`() {
        val merchant = runBlocking { merchantOf(alza(null)) }

        assertThat(merchant?.cleanName).isEqualTo("Alza.cz")
        assertThat(merchant?.logoUrl).isNull()
    }
}
