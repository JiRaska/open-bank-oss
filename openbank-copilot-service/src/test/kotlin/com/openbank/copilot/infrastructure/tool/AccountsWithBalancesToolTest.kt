// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.infrastructure.client.AccountSummary
import com.openbank.copilot.infrastructure.client.AccountsPage
import com.openbank.copilot.infrastructure.client.BalanceDto
import com.openbank.copilot.infrastructure.client.CustomerEdgeRestClient
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** See BalanceToolTest's KDoc — same missing-coverage / bare-array wire-shape finding (#2322). */
class AccountsWithBalancesToolTest {

    private val client = mockk<CustomerEdgeRestClient>()
    private val tool = AccountsWithBalancesTool(client)
    private val args = ObjectMapper().createObjectNode()
    private val accountId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `lists each account with its balances from a bare array response`(): Unit = runBlocking {
        every { client.listAccounts() } returns Uni.createFrom().item(
            AccountsPage(
                data = listOf(
                    AccountSummary(
                        id = accountId,
                        accountNumber = "123456789/0800",
                        accountType = "CURRENT",
                        currencyCode = "CZK",
                        status = "ACTIVE",
                    ),
                ),
            ),
        )
        // Bare array, as balance-service really sends it — not wrapped in an envelope object.
        every { client.getBalances(accountId) } returns Uni.createFrom().item(
            listOf(BalanceDto(currency = "CZK", bookedAmount = BigDecimal("500"), availableAmount = BigDecimal("480"))),
        )

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("123456789/0800")
        assertThat(result.text).contains("CZK: 480 k dispozici")
    }

    @Test
    fun `reports no accounts when the customer has none`(): Unit = runBlocking {
        every { client.listAccounts() } returns Uni.createFrom().item(AccountsPage(data = emptyList()))

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("nemá žádné účty")
    }

    @Test
    fun `degrades a single account's balance failure without failing the whole call`(): Unit = runBlocking {
        every { client.listAccounts() } returns Uni.createFrom().item(
            AccountsPage(
                data = listOf(
                    AccountSummary(
                        id = accountId,
                        accountNumber = "1/0800",
                        accountType = "CURRENT",
                        currencyCode = "CZK",
                        status = "ACTIVE",
                    ),
                ),
            ),
        )
        every { client.getBalances(accountId) } returns Uni.createFrom().failure(
            jakarta.ws.rs.WebApplicationException(502),
        )

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.text).contains("zůstatek nedostupný")
    }

    @Test
    fun `fails the whole call when the account list itself is unavailable`(): Unit = runBlocking {
        every { client.listAccounts() } returns Uni.createFrom().failure(
            jakarta.ws.rs.WebApplicationException(502),
        )

        val result = tool.call(args)

        assertThat(result.isError).isTrue()
        assertThat(result.text).contains("nepodařilo")
    }
}
