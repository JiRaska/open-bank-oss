// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import com.openbank.tax.application.port.out.CeuRegisterRepository
import com.openbank.tax.application.port.out.GsftDataGap
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.domain.ceu.RegisterFact
import com.openbank.tax.domain.gsft.DepositAccount
import com.openbank.tax.domain.gsft.Depositor
import com.openbank.tax.domain.gsft.Holding
import com.openbank.tax.infrastructure.returns.crsfatca.BalanceReportingClient
import com.openbank.tax.infrastructure.returns.crsfatca.CrsFatcaReportingSources
import com.openbank.tax.infrastructure.returns.crsfatca.DepositRosterAccountDto
import com.openbank.tax.infrastructure.returns.crsfatca.RestCrsFatcaReportingSources
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.WebApplicationException
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

    private fun account(id: UUID, party: UUID, iban: String, ccy: String, type: String?) =
        DepositRosterAccountDto(id, iban, party, ccy, type)

    private fun opened(id: UUID, ccy: String, synthetic: Boolean) = RegisterFact.AccountOpened(
        eventId = UUID.randomUUID(),
        accountId = id,
        accountNumber = "CZ65080000001920001453$id".take(24),
        currency = ccy,
        effectiveOn = LocalDate.of(2026, 1, 1),
        recordedAt = Instant.parse("2026-01-01T00:00:00Z"),
        synthetic = synthetic,
    )

    private fun stubRoster() {
        coEvery { sources.depositRoster(day, 200, null) } returns CursorPage(
            listOf(
                account(aCzk, alice, "CZ6508000000192000145399", "CZK", "CURRENT"),
                account(gl, bank, "CZ0000000000000000000001", "CZK", "GL_LIABILITY"),
            ),
            PageInfo(limit = 200, hasNextPage = true, nextCursor = "c1"),
        )
        coEvery { sources.depositRoster(day, 200, "c1") } returns CursorPage(
            listOf(
                account(aEur, alice, "CZ6508000000192000145400", "EUR", "TERM_DEPOSIT"),
                account(botAcc, bot, "CZ6508000000192000145401", "CZK", "SAVINGS"),
            ),
            PageInfo(limit = 200, hasNextPage = false, nextCursor = null),
        )
        coEvery { sources.bookedBalanceStrict(aCzk, "CZK", day) } returns BigDecimal("1500.25")
        coEvery { sources.bookedBalanceStrict(aEur, "EUR", day) } returns BigDecimal("200.00")
        coEvery { sources.bookedBalanceStrict(botAcc, "CZK", day) } returns BigDecimal("10.00")
        coEvery { register.syntheticParties(now) } returns setOf(bot)
        // A REAL holder (alice) whose EUR account's opening event was tainted: the account is synthetic.
        coEvery { register.factsUpTo(LocalDate.of(2026, 11, 3), now) } returns listOf(
            opened(aCzk, "CZK", synthetic = false),
            opened(aEur, "EUR", synthetic = true),
        )
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
                true,
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
        // Literal on purpose: an entries-based expectation moves with the enum and could not fail.
        assertThat(snapshot.gaps).containsExactlyInAnyOrder(
            GsftDataGap.JOINT_HOLDERS,
            GsftDataGap.ACCRUED_INTEREST,
            GsftDataGap.DEPOSITOR_IDENTITY,
            GsftDataGap.EXCLUSION_CATEGORY,
            GsftDataGap.BALANCE_AS_OF_UNVERIFIED,
            GsftDataGap.FX_RATES,
        )
        assertThat(snapshot.complete).isFalse()
        // The GL account is the bank's own: never a deposit, never a balance read.
        coVerify(exactly = 0) { sources.bookedBalanceStrict(gl, any(), any()) }
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
        coEvery { sources.depositRoster(day, 200, null) } returns CursorPage(
            listOf(account(aCzk, alice, "CZ6508000000192000145399", "CZK", null)),
            PageInfo(limit = 200, hasNextPage = false, nextCursor = null),
        )
        coEvery { register.syntheticParties(now) } returns emptySet()

        assertThatThrownBy { runBlocking { adapter.snapshot(day) } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining(aCzk.toString())
    }

    @Test
    fun `a repeated provider continuation refuses a partial roster`() {
        val page = CursorPage<DepositRosterAccountDto>(
            data = emptyList(),
            pagination = PageInfo(limit = 200, hasNextPage = true, nextCursor = "duplicate"),
        )
        coEvery { sources.depositRoster(day, 200, null) } returns page
        coEvery { sources.depositRoster(day, 200, "duplicate") } returns page

        assertThatThrownBy { runBlocking { adapter.snapshot(day) } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("invalid deposit roster continuation")
    }

    @Test
    fun `a duplicate account across pages refuses the roster before reading balances`() {
        val entry = account(aCzk, alice, "CZ6508000000192000145399", "CZK", "CURRENT")
        coEvery { sources.depositRoster(day, 200, null) } returns CursorPage(
            listOf(entry),
            PageInfo(limit = 200, hasNextPage = true, nextCursor = "next"),
        )
        coEvery { sources.depositRoster(day, 200, "next") } returns CursorPage(
            listOf(entry),
            PageInfo(limit = 200, hasNextPage = false, nextCursor = null),
        )

        assertThatThrownBy { runBlocking { adapter.snapshot(day) } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("duplicate account")
        coVerify(exactly = 0) { sources.bookedBalanceStrict(any(), any(), any()) }
    }

    @Test
    fun `the strict balance read fails on 404 where the CRS FATCA read answers zero`() {
        val client = mockk<BalanceReportingClient>()
        val rest = RestCrsFatcaReportingSources(mockk(), mockk(), client)
        coEvery { client.bookedAsOf(aCzk, "CZK", day) } throws WebApplicationException(404)

        assertThatThrownBy { runBlocking { rest.bookedBalanceStrict(aCzk, "CZK", day) } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("404")
        assertThat(runBlocking { rest.bookedBalance(aCzk, "CZK", day) }).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a balance-service failure fails the snapshot`() {
        stubRoster()
        coEvery { sources.bookedBalanceStrict(aEur, "EUR", day) } throws
            ReturnDataUnavailableException("balance-service answered 503")

        assertThatThrownBy { runBlocking { adapter.snapshot(day) } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("503")
    }
}
