// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.application.port.`in`.BalanceUseCase
import com.openbank.balance.application.port.`in`.GetBalanceQuery
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.infrastructure.client.AccountServiceClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Unit tests for the A1 ownership guard in [BalanceResource] (issue #628).
 * Verifies that GET endpoints enforce X-Customer-Party-Id ↔ account.partyId matching
 * and return 404 (not 403) on mismatch to avoid acting as an existence oracle.
 */
class BalanceOwnershipGuardTest {

    private val accountId = UUID.randomUUID()
    private val ownerPartyId = UUID.randomUUID()
    private val otherPartyId = UUID.randomUUID()

    private fun fakeBalance() = Balance(
        id = UUID.randomUUID(),
        accountId = accountId,
        currency = "CZK",
        bookedAmount = BigDecimal("100.00"),
        availableAmount = BigDecimal("100.00"),
        reservedAmount = BigDecimal.ZERO,
        pendingAmount = BigDecimal.ZERO,
        updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        version = 0L,
    )

    // ------ getBalances -------------------------------------------------------

    @Test
    fun `getBalances without header skips guard and delegates to service`(): Unit = runBlocking {
        val svc = mockk<BalanceUseCase>()
        val client = mockk<AccountServiceClient>()
        val resource = BalanceResource(svc, client)
        coEvery { svc.getBalances(accountId) } returns listOf(fakeBalance())

        val response = resource.getBalances(accountId, customerPartyId = null)

        assertThat(response.status).isEqualTo(200)
        coVerify(exactly = 0) { client.getPartyId(any()) }
    }

    @Test
    fun `getBalances with matching party delegates to service`(): Unit = runBlocking {
        val svc = mockk<BalanceUseCase>()
        val client = mockk<AccountServiceClient>()
        val resource = BalanceResource(svc, client)
        coEvery { client.getPartyId(accountId) } returns ownerPartyId
        coEvery { svc.getBalances(accountId) } returns listOf(fakeBalance())

        val response = resource.getBalances(accountId, customerPartyId = ownerPartyId)

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `getBalances with mismatched party returns 404`(): Unit = runBlocking {
        val client = mockk<AccountServiceClient>()
        coEvery { client.getPartyId(accountId) } returns ownerPartyId
        val resource = BalanceResource(mockk(relaxed = true), client)

        val response = resource.getBalances(accountId, customerPartyId = otherPartyId)

        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `getBalances when account not found returns 404`(): Unit = runBlocking {
        val client = mockk<AccountServiceClient>()
        coEvery { client.getPartyId(accountId) } returns null
        val resource = BalanceResource(mockk(relaxed = true), client)

        val response = resource.getBalances(accountId, customerPartyId = ownerPartyId)

        assertThat(response.status).isEqualTo(404)
    }

    // ------ getBalance --------------------------------------------------------

    @Test
    fun `getBalance with matching party delegates to service`(): Unit = runBlocking {
        val svc = mockk<BalanceUseCase>()
        val client = mockk<AccountServiceClient>()
        val resource = BalanceResource(svc, client)
        coEvery { client.getPartyId(accountId) } returns ownerPartyId
        coEvery { svc.getBalance(any<GetBalanceQuery>()) } returns fakeBalance()

        val response = resource.getBalance(accountId, "CZK", asOf = null, customerPartyId = ownerPartyId)

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `getBalance with mismatched party returns 404`(): Unit = runBlocking {
        val client = mockk<AccountServiceClient>()
        coEvery { client.getPartyId(accountId) } returns ownerPartyId
        val resource = BalanceResource(mockk(relaxed = true), client)

        val response = resource.getBalance(accountId, "CZK", asOf = null, customerPartyId = otherPartyId)

        assertThat(response.status).isEqualTo(404)
    }
}
