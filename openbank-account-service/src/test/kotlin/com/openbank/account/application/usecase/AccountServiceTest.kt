// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.CloseAccountCommand
import com.openbank.account.application.port.`in`.ListAccountsQuery
import com.openbank.account.application.port.`in`.OpenAccountCommand
import com.openbank.account.application.port.`in`.SearchAccountsQuery
import com.openbank.account.application.port.out.AccountEventPublisher
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.AccountScreeningUnavailableException
import com.openbank.account.application.port.out.BalanceQueryPort
import com.openbank.account.application.port.out.CurrencyPocketRepository
import com.openbank.account.application.port.out.SanctionsScreenResult
import com.openbank.account.domain.event.AccountCreatedEvent
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AccountServiceTest {

    private lateinit var accountRepository: AccountRepository
    private lateinit var balancePort: BalanceQueryPort
    private lateinit var eventPublisher: AccountEventPublisher
    private lateinit var ibanGenerator: IbanGenerator
    private lateinit var pocketRepository: CurrencyPocketRepository
    private lateinit var sanctionsScreening: AccountSanctionsScreeningPort
    private lateinit var metrics: DomainMetrics

    private lateinit var service: AccountService

    @BeforeEach
    fun setUp() {
        accountRepository = mockk()
        balancePort = mockk()
        eventPublisher = mockk()
        ibanGenerator = mockk()
        pocketRepository = mockk()
        sanctionsScreening = mockk()
        metrics = mockk(relaxed = true)
        service =
            AccountService(
                accountRepository,
                balancePort,
                eventPublisher,
                ibanGenerator,
                pocketRepository,
                sanctionsScreening,
                metrics,
                Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC),
            )
    }

    @Test
    fun `open account publishes created event and does not call balance-service (event-driven init)`(): Unit =
        runBlocking {
            val iban = Iban.of("CZ6508000000192000145399")
            val command = openAccountCommand()

            coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
            every { ibanGenerator.generate(command.currency) } returns iban
            coEvery { accountRepository.existsByIban(iban) } returns false
            coEvery { accountRepository.save(any()) } answers { firstArg() }
            coEvery { pocketRepository.save(any()) } answers { firstArg() }
            coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

            val result = service.openAccount(command)

            assertThat(result.accountNumber).isEqualTo(iban)
            assertThat(result.status).isEqualTo(AccountStatus.ACTIVE)

            coVerify {
                accountRepository.save(
                    match {
                        it.partyId == command.partyId &&
                            it.productId == command.productId &&
                            it.currency == command.currency &&
                            it.status == AccountStatus.ACTIVE
                    },
                )
                eventPublisher.publish(
                    "openbank.accounts.account.created",
                    result.id.toString(),
                    match<AccountCreatedEvent> {
                        it.aggregateId == result.id &&
                            it.accountNumber == iban.value &&
                            it.accountType == command.accountType &&
                            it.partyId == command.partyId &&
                            it.currency == command.currency.code
                    },
                )
            }

            // Balance init is event-driven (ADR-0073, #550): openAccount no longer makes a
            // synchronous balancePort.initialize REST call — balance-service's BalanceInitConsumer
            // seeds the zero balance from the AccountCreated event above. Encode that contract.
            coVerify(exactly = 0) { balancePort.initialize(any(), any(), any()) }
        }

    @Test
    fun `open account fails fast on iban collision`() {
        val iban = Iban.of("CZ6508000000192000145399")
        val command = openAccountCommand()

        coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.existsByIban(iban) } returns true

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("IBAN collision")

        coVerify(exactly = 0) {
            accountRepository.save(any())
            balancePort.initialize(any(), any(), any())
            eventPublisher.publish(any(), any(), any())
        }
    }

    @Test
    fun `list accounts decodes incoming cursor and emits next cursor from last returned item`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val afterId = UUID.randomUUID()
        val accounts = listOf(
            account(id = UUID.randomUUID(), partyId = partyId),
            account(id = UUID.randomUUID(), partyId = partyId),
            account(id = UUID.randomUUID(), partyId = partyId),
        )

        coEvery { accountRepository.findByPartyId(partyId, 3, afterId) } returns accounts

        val page = service.listAccounts(
            ListAccountsQuery(
                partyId = partyId,
                limit = 2,
                afterCursor = CursorEncoder.encode(afterId.toString()),
            ),
        )

        assertThat(page.data).containsExactly(accounts[0], accounts[1])
        assertThat(page.pagination.hasNextPage).isTrue()
        assertThat(CursorEncoder.decode(page.pagination.nextCursor!!)).isEqualTo(accounts[1].id.toString())
    }

    @Test
    fun `search normalizes the fragment (strips spaces, upper-cases) before querying`(): Unit = runBlocking {
        val captured = slot<String>()
        coEvery { accountRepository.searchByIban(capture(captured), any(), null) } returns emptyList()

        service.searchAccounts(SearchAccountsQuery(query = "cz65 0800", limit = 20))

        assertThat(captured.captured).isEqualTo("CZ650800")
    }

    @Test
    fun `search below the minimum fragment length returns empty without touching the repository`(): Unit = runBlocking {
        val page = service.searchAccounts(SearchAccountsQuery(query = " c ", limit = 20))

        assertThat(page.data).isEmpty()
        assertThat(page.pagination.hasNextPage).isFalse()
        coVerify(exactly = 0) { accountRepository.searchByIban(any(), any(), any()) }
    }

    @Test
    fun `search decodes incoming cursor, caps the page and emits next cursor from last item`(): Unit = runBlocking {
        val afterId = UUID.randomUUID()
        val accounts = listOf(
            account(id = UUID.randomUUID(), partyId = UUID.randomUUID()),
            account(id = UUID.randomUUID(), partyId = UUID.randomUUID()),
            account(id = UUID.randomUUID(), partyId = UUID.randomUUID()),
        )
        coEvery { accountRepository.searchByIban("0800", 3, afterId) } returns accounts

        val page = service.searchAccounts(
            SearchAccountsQuery(query = "0800", limit = 2, afterCursor = CursorEncoder.encode(afterId.toString())),
        )

        assertThat(page.data).containsExactly(accounts[0], accounts[1])
        assertThat(page.pagination.hasNextPage).isTrue()
        assertThat(CursorEncoder.decode(page.pagination.nextCursor!!)).isEqualTo(accounts[1].id.toString())
    }

    // ── Sanctions gate tests (ADR-0032 §C) ────────────────────────────────────

    @Test
    fun `openAccount is blocked when sanctions screening returns HIT`() {
        val command = openAccountCommand(legalName = "Sanctioned Person")

        coEvery { sanctionsScreening.screen(any(), any()) } returns
            SanctionsScreenResult("HIT", 0.98, "Sanctioned Person")

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(AccountOpeningBlockedByScreeningException::class.java)

        coVerify(exactly = 0) { accountRepository.save(any()) }
    }

    @Test
    fun `openAccount is blocked when sanctions screening returns REVIEW`() {
        val command = openAccountCommand(legalName = "Fuzzy Match Person")

        coEvery { sanctionsScreening.screen(any(), any()) } returns
            SanctionsScreenResult("REVIEW", 0.72, "Fuzzy Match Person")

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(AccountOpeningBlockedByScreeningException::class.java)

        coVerify(exactly = 0) { accountRepository.save(any()) }
    }

    @Test
    fun `openAccount fails closed when sanctions service is unavailable`() {
        val command = openAccountCommand(legalName = "Test Customer")

        coEvery { sanctionsScreening.screen(any(), any()) } throws
            AccountScreeningUnavailableException(RuntimeException("timeout"))

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(AccountScreeningUnavailableException::class.java)

        coVerify(exactly = 0) { accountRepository.save(any()) }
    }

    @Test
    fun `openAccount proceeds when sanctions screening returns CLEAR`(): Unit = runBlocking {
        val iban = Iban.of("CZ6508000000192000145399")
        val command = openAccountCommand(legalName = "Clean Customer")

        coEvery { sanctionsScreening.screen("Clean Customer", any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.existsByIban(iban) } returns false
        coEvery { accountRepository.save(any()) } answers { firstArg() }
        coEvery { balancePort.initialize(any(), any(), any()) } returns Unit
        coEvery { pocketRepository.save(any()) } answers { firstArg() }
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

        val result = service.openAccount(command)

        assertThat(result.status).isEqualTo(AccountStatus.ACTIVE)
        coVerify(exactly = 1) { sanctionsScreening.screen("Clean Customer", any()) }
    }

    @Test
    fun `openAccount persists sanctions result on saved account (ADR-0032 §C)`(): Unit = runBlocking {
        val iban = Iban.of("CZ6508000000192000145399")
        val command = openAccountCommand(legalName = "Alice Example")
        val savedSlot = slot<Account>()

        coEvery { sanctionsScreening.screen("Alice Example", any()) } returns SanctionsScreenResult("CLEAR", 0.05, null)
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.existsByIban(iban) } returns false
        coEvery { accountRepository.save(capture(savedSlot)) } answers { firstArg() }
        coEvery { balancePort.initialize(any(), any(), any()) } returns Unit
        coEvery { pocketRepository.save(any()) } answers { firstArg() }
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

        service.openAccount(command)

        assertThat(savedSlot.captured.sanctionsStatus).isEqualTo("CLEAR")
        assertThat(savedSlot.captured.sanctionsScreenedAt).isNotNull()
    }

    @Test
    fun `open account counts accountCreated with product type and currency`(): Unit = runBlocking {
        val iban = Iban.of("CZ6508000000192000145399")
        val command = openAccountCommand()
        coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.existsByIban(iban) } returns false
        coEvery { accountRepository.save(any()) } answers { firstArg() }
        coEvery { pocketRepository.save(any()) } answers { firstArg() }
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

        service.openAccount(command)

        verify(exactly = 1) { metrics.accountCreated("CURRENT", "CZK") }
    }

    @Test
    fun `close account counts accountClosed with product type and a normalized reason`(): Unit = runBlocking {
        val acc = account(UUID.randomUUID(), UUID.randomUUID())
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { accountRepository.update(any()) } answers { firstArg() }
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

        service.closeAccount(
            CloseAccountCommand(
                accountId = acc.id,
                reason = "Customer requested closure",
                requestedBy = UUID.randomUUID(),
            ),
        )

        verify(exactly = 1) { metrics.accountClosed("CURRENT", "customer_request") }
    }

    @Test
    fun `close reason is normalized to a bounded low-cardinality set`() {
        assertThat(AccountService.closeReasonTag(null)).isEqualTo("unspecified")
        assertThat(AccountService.closeReasonTag("   ")).isEqualTo("unspecified")
        assertThat(AccountService.closeReasonTag("Suspected FRAUD ring")).isEqualTo("fraud")
        assertThat(AccountService.closeReasonTag("Regulatory / court order")).isEqualTo("regulatory")
        assertThat(AccountService.closeReasonTag("Customer requested")).isEqualTo("customer_request")
        assertThat(AccountService.closeReasonTag("acct-7af3-free-text-9931")).isEqualTo("other")
    }

    private fun openAccountCommand(legalName: String = "Test Customer") = OpenAccountCommand(
        idempotencyKey = "idem-123",
        partyId = UUID.randomUUID(),
        productId = UUID.randomUUID(),
        accountType = AccountType.CURRENT,
        currency = CurrencyCode.CZK,
        requestedBy = UUID.randomUUID(),
        legalName = legalName,
    )

    private fun account(id: UUID, partyId: UUID) = Account(
        id = id,
        accountNumber = Iban.of("CZ6508000000192000145399"),
        accountType = AccountType.CURRENT,
        partyId = partyId,
        productId = UUID.randomUUID(),
        currency = CurrencyCode.CZK,
        status = AccountStatus.ACTIVE,
        openedAt = Instant.now(),
        closedAt = null,
        version = 0L,
    )
}
