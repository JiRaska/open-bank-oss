// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.openbank.treasury.application.port.out.LedgerUnavailableException
import com.openbank.treasury.infrastructure.nostro.AccountBalanceView
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
    private val adapter = LedgerReadAdapter(client)

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
}
