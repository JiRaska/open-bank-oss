// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import com.openbank.tax.application.port.out.CeuRegisterRepository
import com.openbank.tax.application.port.out.GsftDataGap
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.domain.gsft.DepositAccount
import com.openbank.tax.domain.gsft.Depositor
import com.openbank.tax.domain.gsft.Holding
import com.openbank.tax.infrastructure.returns.crsfatca.AccountPage
import com.openbank.tax.infrastructure.returns.crsfatca.AccountSummaryDto
import com.openbank.tax.infrastructure.returns.crsfatca.CrsFatcaReportingSources
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class GsftSnapshotAdapterTest {
    private val day = LocalDate.of(2026, 11, 2)
    private val now = Instant.parse("2026-11-03T08:00:00Z")
    private val sources = mockk<CrsFatcaReportingSources>()
    private val register = mockk<CeuRegisterRepository>()
    private val adapter = GsftSnapshotAdapter(sources, register, Clock.fixed(now, ZoneOffset.UTC))

    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bot = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val bank = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val aCzk = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val aEur = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val botAcc = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val gl = UUID.fromString("00000000-0000-0000-0000-000000000004")

    private fun account(id: UUID, party: UUID, iban: String, ccy: String, type: String?) = AccountSummaryDto(
        id = id,
        accountNumber = iban,
        partyId = party,
        productId = UUID.randomUUID(),
        currencyCode = ccy,
        status = "ACTIVE",
        openedAt = Instant.parse("2026-01-01T00:00:00Z"),
        closedAt = null,
        accountType = type,
    )

    private fun stubRoster() {
        coEvery { sources.activeAccounts(200, null) } returns AccountPage(
            listOf(
                account(aCzk, alice, "CZ6508000000192000145399", "CZK", "CURRENT"),
                account(gl, bank, "CZ0000000000000000000001", "CZK", "GL_LIABILITY"),
            ),
            "c1",
        )
        coEvery { sources.activeAccounts(200, "c1") } returns AccountPage(
            listOf(
                account(aEur, alice, "CZ6508000000192000145400", "EUR", "TERM_DEPOSIT"),
                account(botAcc, bot, "CZ6508000000192000145401", "CZK", "SAVINGS"),
            ),
            null,
        )
        coEvery { sources.bookedBalance(aCzk, "CZK", day) } returns BigDecimal("1500.25")
        coEvery { sources.bookedBalance(aEur, "EUR", day) } returns BigDecimal("200.00")
        coEvery { sources.bookedBalance(botAcc, "CZK", day) } returns BigDecimal("10.00")
        coEvery { register.syntheticParties(now) } returns setOf(bot)
    }

    @Test
    fun `maps the walked deposit roster to exact domain inputs and flags every unsourced field`(): Unit = runBlocking {
        stubRoster()

        val snapshot = adapter.snapshot(day)

        assertThat(snapshot.relevantDay).isEqualTo(day)
        assertThat(snapshot.accounts).containsExactly(
            DepositAccount(
                aCzk,
                "CZ6508000000192000145399",
                "CZK",
                BigDecimal("1500.25"),
                BigDecimal.ZERO,
                listOf(Holding(alice, null)),
                false,
            ),
            DepositAccount(
                aEur,
                "CZ6508000000192000145400",
                "EUR",
                BigDecimal("200.00"),
                BigDecimal.ZERO,
                listOf(Holding(alice, null)),
                false,
            ),
            DepositAccount(
                botAcc,
                "CZ6508000000192000145401",
                "CZK",
                BigDecimal("10.00"),
                BigDecimal.ZERO,
                listOf(Holding(bot, null)),
                true,
            ),
        )
        assertThat(snapshot.depositors).containsExactly(
            Depositor(alice, null, false),
            Depositor(bot, null, true),
        )
        assertThat(snapshot.identities).isEmpty()
        assertThat(snapshot.rates).isNull()
        assertThat(snapshot.gaps).containsExactlyInAnyOrder(*GsftDataGap.entries.toTypedArray())
        assertThat(snapshot.complete).isFalse()
        // The GL account is the bank's own: never a deposit, never a balance read.
        coVerify(exactly = 0) { sources.bookedBalance(gl, any(), any()) }
    }

    @Test
    fun `an incomplete snapshot is refused for filing, naming the gaps`(): Unit = runBlocking {
        stubRoster()

        val snapshot = adapter.snapshot(day)

        assertThatThrownBy { snapshot.requireComplete() }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("ACCRUED_INTEREST")
            .hasMessageContaining("DEPOSITOR_IDENTITY")
            .hasMessageContaining("FX_RATES")
    }

    @Test
    fun `an account with no account type fails the snapshot instead of being guessed`() {
        coEvery { sources.activeAccounts(200, null) } returns AccountPage(
            listOf(account(aCzk, alice, "CZ6508000000192000145399", "CZK", null)),
            null,
        )
        coEvery { register.syntheticParties(now) } returns emptySet()

        assertThatThrownBy { runBlocking { adapter.snapshot(day) } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining(aCzk.toString())
    }

    @Test
    fun `a balance-service failure fails the snapshot`() {
        stubRoster()
        coEvery { sources.bookedBalance(aEur, "EUR", day) } throws
            ReturnDataUnavailableException("balance-service answered 503")

        assertThatThrownBy { runBlocking { adapter.snapshot(day) } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("503")
    }
}
