// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.psd2.application.port.`in`.AccountInformationUseCase
import com.openbank.psd2.application.port.`in`.GetAccountsQuery
import com.openbank.psd2.application.port.`in`.GetBalancesQuery
import com.openbank.psd2.application.port.`in`.GetTransactionsQuery
import com.openbank.psd2.application.port.`in`.TransactionPage
import com.openbank.psd2.domain.model.BookingStatus
import com.openbank.psd2.domain.model.ObAmount
import com.openbank.psd2.domain.model.ObBalance
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The bespoke `/open-banking/v2` AIS surface: [AisResource] delegates to [AccountInformationUseCase]
 * and only shapes the (non-Berlin) response envelope. `tppId` is read off the request property set
 * upstream by `EidasMtlsFilter`; a missing property (filter not run / not authorized) short-circuits.
 */
class AisResourceTest {

    private val ais = mockk<AccountInformationUseCase>()
    private val resource = AisResource(ais)

    private fun ctxWithTpp(tppId: String?): ContainerRequestContext {
        val ctx = mockk<ContainerRequestContext>()
        every { ctx.getProperty("tppId") } returns tppId
        return ctx
    }

    @Test
    fun `getAccounts returns 401 CERTIFICATE_MISSING when tppId property absent`(): Unit = runBlocking {
        val response = resource.getAccounts("consent-1", ctxWithTpp(null))

        assertThat(response.status).isEqualTo(401)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = body["tppMessages"] as List<Map<String, Any?>>
        assertThat(messages[0]["code"]).isEqualTo("CERTIFICATE_MISSING")
    }

    @Test
    fun `getAccounts wraps the use-case result under an accounts key`(): Unit = runBlocking {
        val accounts = listOf(
            com.openbank.psd2.domain.model.ObAccount(
                resourceId = "acc-1",
                iban = "CZ6508000000192000145399",
                currency = "CZK",
                ownerName = "Jan",
                name = "Main",
                product = "Current",
                cashAccountType = "CACC",
            ),
        )
        coEvery { ais.getAccounts(GetAccountsQuery("consent-1", "tpp-1")) } returns accounts

        val response = resource.getAccounts("consent-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body["accounts"]).isEqualTo(accounts)
    }

    @Test
    fun `getBalances returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.getBalances("acc-1", "consent-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getBalances wraps account iban and balances`(): Unit = runBlocking {
        val balances = listOf(
            ObBalance(ObAmount("CZK", BigDecimal("10.00")), "closingBooked", null, null),
        )
        coEvery { ais.getBalances(GetBalancesQuery("consent-1", "tpp-1", "acc-1")) } returns balances

        val response = resource.getBalances("acc-1", "consent-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val account = body["account"] as Map<String, Any?>
        assertThat(account["iban"]).isEqualTo("acc-1")
        assertThat(body["balances"]).isEqualTo(balances)
    }

    @Test
    fun `getTransactions returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.getTransactions("acc-1", "consent-1", null, null, null, null, null, ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getTransactions parses filters, defaults bookingStatus and limit, and omits _links without a cursor`(
    ): Unit = runBlocking {
        coEvery {
            ais.getTransactions(
                GetTransactionsQuery(
                    consentId = "consent-1",
                    tppId = "tpp-1",
                    accountId = "acc-1",
                    dateFrom = null,
                    dateTo = null,
                    bookingStatus = BookingStatus.BOOKED,
                    limit = 50,
                    afterCursor = null,
                ),
            )
        } returns TransactionPage(booked = emptyList(), pending = emptyList(), nextCursor = null)

        val response = resource.getTransactions("acc-1", "consent-1", null, null, null, null, null, ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body).doesNotContainKey("_links")
    }

    @Test
    fun `getTransactions adds a next-page link when a cursor is present`(): Unit = runBlocking {
        coEvery {
            ais.getTransactions(
                GetTransactionsQuery(
                    consentId = "consent-1",
                    tppId = "tpp-1",
                    accountId = "acc-1",
                    dateFrom = java.time.LocalDate.of(2024, 1, 1),
                    dateTo = java.time.LocalDate.of(2024, 1, 31),
                    bookingStatus = BookingStatus.BOTH,
                    limit = 10,
                    afterCursor = "cur-1",
                ),
            )
        } returns TransactionPage(booked = emptyList(), pending = emptyList(), nextCursor = "cur-2")

        val response = resource.getTransactions(
            "acc-1",
            "consent-1",
            "2024-01-01",
            "2024-01-31",
            "both",
            10,
            "cur-1",
            ctxWithTpp("tpp-1"),
        )

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val links = body["_links"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val next = links["next"] as Map<String, String>
        assertThat(next["href"]).isEqualTo("?afterCursor=cur-2")
    }
}
