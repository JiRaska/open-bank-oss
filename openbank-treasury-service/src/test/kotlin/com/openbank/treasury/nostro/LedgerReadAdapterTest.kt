// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.openbank.treasury.application.port.out.LedgerUnavailableException
import com.openbank.treasury.infrastructure.nostro.AccountBalanceView
import com.openbank.treasury.infrastructure.nostro.AccountingClassMappingView
import com.openbank.treasury.infrastructure.nostro.LedgerAccountingClassRestClient
import com.openbank.treasury.infrastructure.nostro.LedgerReadAdapter
import com.openbank.treasury.infrastructure.nostro.LedgerReadRestClient
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A 404 from the balance read is two different answers (#11113 review): ledger's unknown-account
 * body means "not stated"; a 404 for a route the ledger does not serve (treasury deployed first)
 * must be an upstream failure, never a NULL that blanks balances with a false reason.
 */
class LedgerReadAdapterTest {

    private val date = LocalDate.parse("2026-09-25")
    private val client = mockk<LedgerReadRestClient>()
    private val classes = mockk<LedgerAccountingClassRestClient>()
    private val adapter = LedgerReadAdapter(client, classes, postByClass = true)

    private fun answer404(body: String?) {
        val response = mockk<Response>(relaxed = true)
        every { response.status } returns 404
        every { response.readEntity(String::class.java) } returns body
        every { client.accountBalance(any(), any(), any()) } returns
            Uni.createFrom().failure(WebApplicationException(response))
    }

    @Test
    fun `ledger's unknown-account 404 is the one NULL`() = runBlocking<Unit> {
        answer404("""{"error":"GL account 1002 not found"}""")
        assertThat(adapter.accountBalance("1002", "EUR", date)).isNull()
    }

    @Test
    fun `a 404 for a route the ledger does not serve is an upstream failure, not NULL`() {
        listOf(
            null,
            "",
            "<html>Not Found</html>",
            """{"error":"NOT_FOUND"}""",
            """{"error":"GL account 9999 not found"}""",
        )
            .forEach { body ->
                answer404(body)
                assertThatThrownBy { runBlocking { adapter.accountBalance("1002", "EUR", date) } }
                    .describedAs("body %s", body)
                    .isInstanceOf(LedgerUnavailableException::class.java)
                    .hasMessageContaining("404")
            }
    }

    @Test
    fun `a stated balance is returned when the echo agrees`() = runBlocking<Unit> {
        every { client.accountBalance("1001", "2026-09-25", "CZK") } returns Uni.createFrom().item(
            AccountBalanceView(
                "1001",
                "CZK",
                "2026-09-25",
                "REAL_ONLY",
                BigDecimal("10"),
                BigDecimal("3"),
                BigDecimal("7"),
            ),
        )
        assertThat(adapter.accountBalance("1001", "CZK", date)).isEqualByComparingTo("7")
    }

    @Test
    fun `the nostro of a currency is whatever the ledger map says, not a literal`() = runBlocking<Unit> {
        val moved = java.util.UUID.fromString("a0000000-0000-0000-0000-000000009001")
        every { classes.list() } returns Uni.createFrom().item(
            listOf(
                AccountingClassMappingView("NOSTRO_ACCOUNT", "PRINCIPAL", "CZK", moved, "9001"),
                AccountingClassMappingView("NOSTRO_ACCOUNT", "PRINCIPAL", "EUR", java.util.UUID.randomUUID(), "1002"),
                // other classes and roles are not nostros
                AccountingClassMappingView(
                    "INTERBANK_PLACEMENT",
                    "PRINCIPAL",
                    "CZK",
                    java.util.UUID.randomUUID(),
                    "1500",
                ),
                AccountingClassMappingView(
                    "NOSTRO_ACCOUNT",
                    "ACCRUED_INTEREST",
                    "USD",
                    java.util.UUID.randomUUID(),
                    "1999",
                ),
            ),
        )
        val nostros = adapter.nostroAccounts()
        assertThat(nostros.keys).containsExactlyInAnyOrder("CZK", "EUR")
        assertThat(nostros.getValue("CZK").glCode).isEqualTo("9001")
        assertThat(nostros.getValue("CZK").glAccountId).isEqualTo(moved)
    }

    @Test
    fun `a ledger that does not serve the class map is unavailable, never an empty nostro set`() {
        val response = mockk<Response>(relaxed = true)
        every { response.status } returns 404
        every { classes.list() } returns Uni.createFrom().failure(WebApplicationException(response))
        assertThatThrownBy { runBlocking { adapter.nostroAccounts() } }
            .isInstanceOf(LedgerUnavailableException::class.java)
    }

    @Test
    fun `with the switch off an older ledger's 404 on the class map does not stop reconciliation`() =
        runBlocking<Unit> {
            val response = mockk<Response>(relaxed = true)
            every { response.status } returns 404
            every { classes.list() } returns Uni.createFrom().failure(WebApplicationException(response))
            val legacy = LedgerReadAdapter(client, classes, postByClass = false)

            val nostros = legacy.nostroAccounts()

            assertThat(nostros.getValue("CZK").glCode).isEqualTo("1001")
            assertThat(nostros.getValue("EUR").glCode).isEqualTo("1002")
            assertThat(nostros.getValue("CZK").glAccountId.toString()).isEqualTo("a0000000-0000-0000-0000-000000001001")
            io.mockk.verify(exactly = 0) { classes.list() }
        }

    @Test
    fun `with the switch off the line read finds the nostro account without touching the class map`() =
        runBlocking<Unit> {
            every { classes.list() } returns Uni.createFrom().failure(IllegalStateException("must not be called"))
            every { client.list(any(), any(), any(), any()) } returns Uni.createFrom().item(
                com.openbank.treasury.infrastructure.nostro.JournalPage(),
            )
            val legacy = LedgerReadAdapter(client, classes, postByClass = false)
            assertThat(legacy.nostroLines(legacy.nostroAccounts().getValue("CZK"), date, date)).isEmpty()
        }

    @Test
    fun `one reconcile run reads the class map once and nothing is cached between runs`() = runBlocking<Unit> {
        every { classes.list() } returns Uni.createFrom().item(
            listOf(
                AccountingClassMappingView("NOSTRO_ACCOUNT", "PRINCIPAL", "CZK", java.util.UUID.randomUUID(), "1001"),
            ),
        )
        every { client.list(any(), any(), any(), any()) } returns Uni.createFrom().item(
            com.openbank.treasury.infrastructure.nostro.JournalPage(),
        )
        // run 1: resolve once, hand the account to the line read
        val nostro = adapter.nostroAccounts().getValue("CZK")
        adapter.nostroLines(nostro, date, date)
        io.mockk.verify(exactly = 1) { classes.list() }
        // run 2 sees the ledger afresh
        adapter.nostroAccounts()
        io.mockk.verify(exactly = 2) { classes.list() }
    }
}
