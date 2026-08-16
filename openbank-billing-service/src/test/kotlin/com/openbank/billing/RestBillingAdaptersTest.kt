// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.infrastructure.adapter.RestAccountContextPort
import com.openbank.billing.infrastructure.adapter.RestAccountPartyLookupPort
import com.openbank.billing.infrastructure.adapter.RestBillableAccountDiscoveryPort
import com.openbank.billing.infrastructure.adapter.RestProductCatalogPort
import com.openbank.billing.infrastructure.client.AccountDto
import com.openbank.billing.infrastructure.client.AccountPageDto
import com.openbank.billing.infrastructure.client.AccountPageInfoDto
import com.openbank.billing.infrastructure.client.AccountRestClient
import com.openbank.billing.infrastructure.client.BalanceDto
import com.openbank.billing.infrastructure.client.BalanceRestClient
import com.openbank.billing.infrastructure.client.FeeDto
import com.openbank.billing.infrastructure.client.ProductCatalogRestClient
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Unit coverage for the read-path adapters (ADR-0143 phase 2c), with mocked REST clients. */
class RestBillingAdaptersTest {

    @Test
    fun `resolve builds the FeeContext from account + balance reads`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        val balances = mockk<BalanceRestClient>()
        every { accounts.getAccount("acc1") } returns
            Uni.createFrom().item(AccountDto("acc1", "prod-1", "CZK", "ACTIVE"))
        every { balances.getBalance("acc1", "CZK") } returns
            Uni.createFrom().item(BalanceDto("acc1", "CZK", BigDecimal("1000")))

        val r = RestAccountContextPort(accounts, balances).resolve("acc1", "CZK")

        assertThat(r).isNotNull
        assertThat(r!!.productId).isEqualTo("prod-1")
        assertThat(r.context.balance).isEqualByComparingTo("1000")
        assertThat(r.context.currency).isEqualTo("CZK")
        assertThat(r.context.segment).isNull()
        assertThat(r.context.monthlyTurnover).isNull()
    }

    @Test
    fun `resolve fails closed (null) when the account read errors`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        val balances = mockk<BalanceRestClient>()
        every { accounts.getAccount(any()) } returns Uni.createFrom().failure(RuntimeException("account-service down"))

        val r = RestAccountContextPort(accounts, balances).resolve("acc1", "CZK")

        assertThat(r).isNull()
    }

    @Test
    fun `resolve leaves balance null when the balance read errors`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        val balances = mockk<BalanceRestClient>()
        every { accounts.getAccount("acc1") } returns
            Uni.createFrom().item(AccountDto("acc1", "prod-1", "EUR", "ACTIVE"))
        every { balances.getBalance("acc1", "EUR") } returns Uni.createFrom().failure(RuntimeException("balance down"))

        val r = RestAccountContextPort(accounts, balances).resolve("acc1", "EUR")

        assertThat(r).isNotNull
        assertThat(r!!.context.balance).isNull()
    }

    @Test
    fun `activeAccounts maps ids and forwards the cursor only while more pages exist`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.listActiveAccounts(100, null) } returns Uni.createFrom().item(
            AccountPageDto(
                data = listOf(AccountDto("acc-1", "prod-1", "CZK"), AccountDto("acc-2", "prod-1", "CZK")),
                pagination = AccountPageInfoDto(hasNextPage = true, nextCursor = "c1"),
            ),
        )
        every { accounts.listActiveAccounts(100, "c1") } returns Uni.createFrom().item(
            AccountPageDto(
                data = listOf(AccountDto("acc-3", "prod-2", "CZK")),
                pagination = AccountPageInfoDto(hasNextPage = false, nextCursor = null),
            ),
        )
        val port = RestBillableAccountDiscoveryPort(accounts)

        val first = port.activeAccounts(100, null)
        assertThat(first.accountIds).containsExactly("acc-1", "acc-2")
        assertThat(first.nextCursor).isEqualTo("c1")

        val last = port.activeAccounts(100, "c1")
        assertThat(last.accountIds).containsExactly("acc-3")
        assertThat(last.nextCursor).isNull()
    }

    @Test
    fun `activeAccounts propagates a failed page read instead of failing open`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.listActiveAccounts(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("account-service down"))
        val port = RestBillableAccountDiscoveryPort(accounts)

        val thrown = runCatching { port.activeAccounts(100, null) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `billableFees filters by currency and maps to the domain`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getProductFees("prod-1") } returns Uni.createFrom().item(
            listOf(
                FeeDto("f1", "Maintenance", "MONTHLY", BigDecimal("5"), "CZK", false, null),
                FeeDto("f2", "FX", "TRANSACTION", BigDecimal("2"), "EUR", false, null),
            ),
        )

        val fees = RestProductCatalogPort(catalog).billableFees("prod-1", "CZK")

        assertThat(fees).hasSize(1)
        assertThat(fees.single().feeId).isEqualTo("f1")
        assertThat(fees.single().amount).isEqualByComparingTo("5")
    }

    @Test
    fun `billableFees propagates a failed catalog read instead of failing open`(): Unit = runBlocking {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getProductFees(any()) } returns
            Uni.createFrom().failure(RuntimeException("product-catalog down"))

        val thrown = runCatching { RestProductCatalogPort(catalog).billableFees("prod-1", "CZK") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `partyIdFor reads partyId off the account read`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.getAccount("acc1") } returns
            Uni.createFrom().item(AccountDto("acc1", "prod-1", "CZK", "ACTIVE", "party-1"))

        val partyId = RestAccountPartyLookupPort(accounts).partyIdFor("acc1")

        assertThat(partyId).isEqualTo("party-1")
    }

    @Test
    fun `partyIdFor fails closed (null) when the account read errors`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.getAccount(any()) } returns Uni.createFrom().failure(RuntimeException("account-service down"))

        val partyId = RestAccountPartyLookupPort(accounts).partyIdFor("acc1")

        assertThat(partyId).isNull()
    }

    @Test
    fun `partyIdFor returns null when the account response carries no partyId`(): Unit = runBlocking {
        val accounts = mockk<AccountRestClient>()
        every { accounts.getAccount("acc1") } returns
            Uni.createFrom().item(AccountDto("acc1", "prod-1", "CZK", "ACTIVE", null))

        val partyId = RestAccountPartyLookupPort(accounts).partyIdFor("acc1")

        assertThat(partyId).isNull()
    }
}
