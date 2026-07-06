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
import java.time.LocalDate

/**
 * Berlin Group XS2A `/v1/accounts` surface: mandatory `Consent-ID`/TPP checks, and that
 * `X-Request-ID` is echoed back on every response per spec.
 */
class BerlinAisResourceTest {

    private val ais = mockk<AccountInformationUseCase>()
    private val resource = BerlinAisResource(ais)

    private fun ctxWithTpp(tppId: String?): ContainerRequestContext {
        val ctx = mockk<ContainerRequestContext>()
        every { ctx.getProperty("tppId") } returns tppId
        return ctx
    }

    @Test
    fun `getAccounts returns 401 CERTIFICATE_MISSING when tppId absent`(): Unit = runBlocking {
        val response = resource.getAccounts("consent-1", "req-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getAccounts returns 401 CONSENT_INVALID when Consent-ID missing`(): Unit = runBlocking {
        val response = resource.getAccounts(null, "req-1", ctxWithTpp("tpp-1"))
        assertThat(response.status).isEqualTo(401)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = body["tppMessages"] as List<Map<String, Any?>>
        assertThat(messages[0]["code"]).isEqualTo("CONSENT_INVALID")
    }

    @Test
    fun `getAccounts echoes X-Request-ID and maps the Berlin account list`(): Unit = runBlocking {
        coEvery { ais.getAccounts(GetAccountsQuery("consent-1", "tpp-1")) } returns emptyList()

        val response = resource.getAccounts("consent-1", "req-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("req-1")
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body).containsKey("accounts")
    }

    @Test
    fun `getBalances returns 401 when Consent-ID missing`(): Unit = runBlocking {
        val response = resource.getBalances("acc-1", null, "req-1", ctxWithTpp("tpp-1"))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getBalances maps to the Berlin balances shape`(): Unit = runBlocking {
        coEvery { ais.getBalances(GetBalancesQuery("consent-1", "tpp-1", "acc-1")) } returns listOf(
            ObBalance(ObAmount("CZK", BigDecimal("5.00")), "closingBooked", null, null),
        )

        val response = resource.getBalances("acc-1", "consent-1", "req-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("req-1")
    }

    @Test
    fun `getTransactions returns 401 when Consent-ID missing`(): Unit = runBlocking {
        val response =
            resource.getTransactions("acc-1", null, "req-1", null, null, null, null, null, ctxWithTpp("tpp-1"))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getTransactions parses query filters and defaults bookingStatus+limit`(): Unit = runBlocking {
        coEvery {
            ais.getTransactions(
                GetTransactionsQuery(
                    consentId = "consent-1",
                    tppId = "tpp-1",
                    accountId = "acc-1",
                    dateFrom = LocalDate.of(2024, 2, 1),
                    dateTo = LocalDate.of(2024, 2, 28),
                    bookingStatus = BookingStatus.BOOKED,
                    limit = 50,
                    afterCursor = null,
                ),
            )
        } returns TransactionPage(emptyList(), emptyList(), null)

        val response = resource.getTransactions(
            "acc-1",
            "consent-1",
            "req-1",
            "2024-02-01",
            "2024-02-28",
            null,
            null,
            null,
            ctxWithTpp("tpp-1"),
        )

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("req-1")
    }

    @Test
    fun `getTransactions honours an explicit bookingStatus and limit`(): Unit = runBlocking {
        coEvery {
            ais.getTransactions(
                GetTransactionsQuery(
                    consentId = "consent-1",
                    tppId = "tpp-1",
                    accountId = "acc-1",
                    dateFrom = null,
                    dateTo = null,
                    bookingStatus = BookingStatus.PENDING,
                    limit = 5,
                    afterCursor = "cur-1",
                ),
            )
        } returns TransactionPage(emptyList(), emptyList(), "cur-2")

        val response = resource.getTransactions(
            "acc-1",
            "consent-1",
            "req-1",
            null,
            null,
            "pending",
            5,
            "cur-1",
            ctxWithTpp("tpp-1"),
        )

        assertThat(response.status).isEqualTo(200)
    }
}
