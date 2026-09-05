// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.CloseAccountCommand
import com.openbank.account.application.port.`in`.ListAccountsQuery
import com.openbank.account.application.port.`in`.ListActiveAccountsQuery
import com.openbank.account.application.port.`in`.OpenAccountCommand
import com.openbank.account.application.port.`in`.SearchAccountsQuery
import com.openbank.account.application.port.out.AccountEventPublisher
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.AccountScreeningUnavailableException
import com.openbank.account.application.port.out.BalanceQueryPort
import com.openbank.account.application.port.out.CatalogProduct
import com.openbank.account.application.port.out.CurrencyPocketRepository
import com.openbank.account.application.port.out.NotificationRequestPort
import com.openbank.account.application.port.out.ProductCatalogPort
import com.openbank.account.application.port.out.ProductLookupResult
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
    private lateinit var productCatalog: ProductCatalogPort
    private lateinit var metrics: DomainMetrics
    private lateinit var notificationRequestPort: NotificationRequestPort
    private lateinit var service: AccountService

    @BeforeEach
    fun setUp() {
        accountRepository = mockk()
        balancePort = mockk()
        eventPublisher = mockk()
        ibanGenerator = mockk()
        pocketRepository = mockk()
        sanctionsScreening = mockk()
        productCatalog = mockk()
        metrics = mockk(relaxed = true)
        notificationRequestPort = mockk(relaxed = true)
        // Default: product-catalog unreachable — the fail-open path, so every pre-existing test
        // that doesn't care about product validation keeps passing unchanged.
        coEvery { productCatalog.findById(any()) } returns ProductLookupResult.Unavailable
        service =
            AccountService(
                accountRepository,
                balancePort,
                eventPublisher,
                ibanGenerator,
                pocketRepository,
                sanctionsScreening,
                productCatalog,
                metrics,
                Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC),
                notificationRequestPort,
            )
    }

    @Test
    fun `open account publishes created event and does not call balance-service (event-driven init)`(): Unit =
        runBlocking {
            val iban = Iban.of("CZ6508000000192000145399")
            val command = openAccountCommand()

            coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
            every { ibanGenerator.generate(command.currency) } returns iban
            coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
            coEvery { accountRepository.existsByIban(iban) } returns false
            coEvery { accountRepository.saveNewAccount(any(), any(), any()) } answers { firstArg() }
            coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

            val result = service.openAccount(command)

            assertThat(result.accountNumber).isEqualTo(iban)
            assertThat(result.status).isEqualTo(AccountStatus.ACTIVE)

            coVerify {
                accountRepository.saveNewAccount(
                    match {
                        it.partyId == command.partyId &&
                            it.productId == command.productId &&
                            it.currency == command.currency &&
                            it.status == AccountStatus.ACTIVE
                    },
                    match { it.isPrimary && it.currency == command.currency },
                    eq(command.idempotencyKey),
                )
                eventPublisher.publish(
                    "openbank.accounts.account.created",
                    result.id.toString(),
                    match<AccountCreatedEvent> {
                        it.aggregateId == result.id &&
                            it.accountNumber == iban.value &&
                            it.accountType == command.accountType &&
                            it.partyId == command.partyId &&
                            it.currency == command.currency.code &&
                            // AuditConsumer attribution (#3994/#5256): the real construction site
                            // passes this explicitly rather than relying on the default silently
                            // working (#5255's own discipline).
                            it.sourceService == "account-service"
                    },
                )
            }

            // Balance init is event-driven (ADR-0267, #550): openAccount no longer makes a
            // synchronous balancePort.initialize REST call — balance-service's BalanceInitConsumer
            // seeds the zero balance from the AccountCreated event above. Encode that contract.
            coVerify(exactly = 0) { balancePort.initialize(any(), any(), any()) }
        }

    @Test
    fun `open account fails fast on iban collision`() {
        val iban = Iban.of("CZ6508000000192000145399")
        val command = openAccountCommand()

        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.existsByIban(iban) } returns true

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("IBAN collision")

        coVerify(exactly = 0) {
            accountRepository.saveNewAccount(any(), any(), any())
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

    @Test
    fun `list active accounts decodes incoming cursor and emits next cursor from last item`(): Unit = runBlocking {
        val afterId = UUID.randomUUID()
        val accounts = listOf(
            account(id = UUID.randomUUID(), partyId = UUID.randomUUID()),
            account(id = UUID.randomUUID(), partyId = UUID.randomUUID()),
            account(id = UUID.randomUUID(), partyId = UUID.randomUUID()),
        )
        coEvery { accountRepository.findActive(3, afterId) } returns accounts

        val page = service.listActiveAccounts(
            ListActiveAccountsQuery(limit = 2, afterCursor = CursorEncoder.encode(afterId.toString())),
        )

        assertThat(page.data).containsExactly(accounts[0], accounts[1])
        assertThat(page.pagination.hasNextPage).isTrue()
        assertThat(CursorEncoder.decode(page.pagination.nextCursor!!)).isEqualTo(accounts[1].id.toString())
    }

    @Test
    fun `list active accounts caps the page size at the sweep maximum`(): Unit = runBlocking {
        coEvery { accountRepository.findActive(any(), null) } returns emptyList()

        service.listActiveAccounts(ListActiveAccountsQuery(limit = 9999))

        coVerify { accountRepository.findActive(AccountService.MAX_ACTIVE_LIST_LIMIT + 1, null) }
    }

    // ── Sanctions gate tests (ADR-0032 §C) ────────────────────────────────────

    @Test
    fun `openAccount is blocked when sanctions screening returns HIT`() {
        val command = openAccountCommand(legalName = "Sanctioned Person")

        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen(any(), any()) } returns
            SanctionsScreenResult("HIT", 0.98, "Sanctioned Person")

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(AccountOpeningBlockedByScreeningException::class.java)

        coVerify(exactly = 0) { accountRepository.saveNewAccount(any(), any(), any()) }
        // #4348 hazard: a blocked open must never share the accountCreated count with a real one.
        verify(exactly = 0) { metrics.accountCreated(any(), any()) }
    }

    @Test
    fun `openAccount is blocked when sanctions screening returns REVIEW`() {
        val command = openAccountCommand(legalName = "Fuzzy Match Person")

        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen(any(), any()) } returns
            SanctionsScreenResult("REVIEW", 0.72, "Fuzzy Match Person")

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(AccountOpeningBlockedByScreeningException::class.java)

        coVerify(exactly = 0) { accountRepository.saveNewAccount(any(), any(), any()) }
        verify(exactly = 0) { metrics.accountCreated(any(), any()) }
    }

    @Test
    fun `openAccount fails closed when sanctions service is unavailable`() {
        val command = openAccountCommand(legalName = "Test Customer")

        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen(any(), any()) } throws
            AccountScreeningUnavailableException(RuntimeException("timeout"))

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(AccountScreeningUnavailableException::class.java)

        coVerify(exactly = 0) { accountRepository.saveNewAccount(any(), any(), any()) }
        verify(exactly = 0) { metrics.accountCreated(any(), any()) }
    }

    @Test
    fun `openAccount proceeds when sanctions screening returns CLEAR`(): Unit = runBlocking {
        val iban = Iban.of("CZ6508000000192000145399")
        val command = openAccountCommand(legalName = "Clean Customer")

        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen("Clean Customer", any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.existsByIban(iban) } returns false
        coEvery { accountRepository.saveNewAccount(any(), any(), any()) } answers { firstArg() }
        coEvery { balancePort.initialize(any(), any(), any()) } returns Unit
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

        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen("Alice Example", any()) } returns SanctionsScreenResult("CLEAR", 0.05, null)
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.existsByIban(iban) } returns false
        coEvery { accountRepository.saveNewAccount(capture(savedSlot), any(), any()) } answers { firstArg() }
        coEvery { balancePort.initialize(any(), any(), any()) } returns Unit
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

        service.openAccount(command)

        assertThat(savedSlot.captured.sanctionsStatus).isEqualTo("CLEAR")
        assertThat(savedSlot.captured.sanctionsScreenedAt).isNotNull()
    }

    // --- Product validation at account-open (issue #668) --------------------------------------

    @Test
    fun `openAccount refuses a product that product-catalog confirms does not exist`() {
        val command = openAccountCommand()
        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        coEvery { productCatalog.findById(command.productId) } returns ProductLookupResult.NotFound

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(ProductNotEligibleException::class.java)
            .hasMessageContaining("does not exist")

        coVerify(exactly = 0) { accountRepository.saveNewAccount(any(), any(), any()) }
    }

    @Test
    fun `openAccount refuses a product that is not ACTIVE`() {
        val command = openAccountCommand()
        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        coEvery { productCatalog.findById(command.productId) } returns
            ProductLookupResult.Found(CatalogProduct(command.productId, "SAVINGS_STANDARD", "DRAFT", "CZK"))

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(ProductNotEligibleException::class.java)
            .hasMessageContaining("DRAFT")

        coVerify(exactly = 0) { accountRepository.saveNewAccount(any(), any(), any()) }
    }

    @Test
    fun `openAccount proceeds when product-catalog confirms an ACTIVE product`(): Unit = runBlocking {
        val iban = Iban.of("CZ6508000000192000145399")
        val command = openAccountCommand()
        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        coEvery { productCatalog.findById(command.productId) } returns
            ProductLookupResult.Found(CatalogProduct(command.productId, "SAVINGS_STANDARD", "ACTIVE", "CZK"))
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.existsByIban(iban) } returns false
        coEvery { accountRepository.saveNewAccount(any(), any(), any()) } answers { firstArg() }
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

        val result = service.openAccount(command)

        assertThat(result.status).isEqualTo(AccountStatus.ACTIVE)
    }

    @Test
    fun `openAccount refuses a confirmed product currency mismatch`() {
        val command = openAccountCommand()
        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        coEvery { productCatalog.findById(command.productId) } returns
            ProductLookupResult.Found(CatalogProduct(command.productId, "SAVINGS_STANDARD", "ACTIVE", "EUR"))

        assertThatThrownBy { runBlocking { service.openAccount(command) } }
            .isInstanceOf(ProductNotEligibleException::class.java)
            .hasMessageContaining("EUR, not CZK")

        coVerify(exactly = 0) { accountRepository.saveNewAccount(any(), any(), any()) }
    }

    @Test
    fun `openAccount proceeds without validation when product-catalog is unavailable`(): Unit = runBlocking {
        val iban = Iban.of("CZ6508000000192000145399")
        val command = openAccountCommand()
        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        coEvery { productCatalog.findById(command.productId) } returns ProductLookupResult.Unavailable
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.existsByIban(iban) } returns false
        coEvery { accountRepository.saveNewAccount(any(), any(), any()) } answers { firstArg() }
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

        val result = service.openAccount(command)

        assertThat(result.status).isEqualTo(AccountStatus.ACTIVE)
    }

    @Test
    fun `open account counts accountCreated with product type and currency`(): Unit = runBlocking {
        val iban = Iban.of("CZ6508000000192000145399")
        val command = openAccountCommand()
        coEvery { sanctionsScreening.screen(any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        every { ibanGenerator.generate(command.currency) } returns iban
        coEvery { accountRepository.findByIdempotencyKey(any()) } returns null
        coEvery { accountRepository.existsByIban(iban) } returns false
        coEvery { accountRepository.saveNewAccount(any(), any(), any()) } answers { firstArg() }
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
        coEvery { balancePort.getByAccount(acc.id) } returns emptyList()

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

    // ── Savings goal (ADR-0153) ────────────────────────────────────────────────

    @Test
    fun `updateSavingsGoal persists the goal fields on the account`(): Unit = runBlocking {
        val acc = account(UUID.randomUUID(), UUID.randomUUID())
        coEvery { accountRepository.findById(acc.id) } returns acc
        val captured = slot<Account>()
        coEvery { accountRepository.update(capture(captured)) } answers { firstArg() }

        val result = service.updateSavingsGoal(
            com.openbank.account.application.port.`in`.UpdateSavingsGoalCommand(
                accountId = acc.id,
                name = "Nová lednička",
                targetMinorUnits = 4_000_000L,
                targetDate = java.time.LocalDate.of(2026, 12, 1),
                requestedBy = UUID.randomUUID(),
            ),
        )

        assertThat(captured.captured.goalName).isEqualTo("Nová lednička")
        assertThat(captured.captured.goalTargetMinorUnits).isEqualTo(4_000_000L)
        assertThat(captured.captured.goalTargetDate).isEqualTo(java.time.LocalDate.of(2026, 12, 1))
        assertThat(result.goalName).isEqualTo("Nová lednička")
    }

    @Test
    fun `updateSavingsGoal rejects a non-positive target`(): Unit = runBlocking {
        val acc = account(UUID.randomUUID(), UUID.randomUUID())
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy {
            runBlocking {
                service.updateSavingsGoal(
                    com.openbank.account.application.port.`in`.UpdateSavingsGoalCommand(
                        accountId = acc.id,
                        name = "Cíl",
                        targetMinorUnits = 0L,
                        targetDate = null,
                        requestedBy = UUID.randomUUID(),
                    ),
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)

        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    @Test
    fun `updateSavingsGoal rejects a blank name`(): Unit = runBlocking {
        val acc = account(UUID.randomUUID(), UUID.randomUUID())
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy {
            runBlocking {
                service.updateSavingsGoal(
                    com.openbank.account.application.port.`in`.UpdateSavingsGoalCommand(
                        accountId = acc.id,
                        name = "   ",
                        targetMinorUnits = 1_000L,
                        targetDate = null,
                        requestedBy = UUID.randomUUID(),
                    ),
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)

        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    @Test
    fun `clearSavingsGoal nulls all three goal fields`(): Unit = runBlocking {
        val acc = account(UUID.randomUUID(), UUID.randomUUID()).copy(
            goalName = "Dovolená",
            goalTargetMinorUnits = 5_000_000L,
            goalTargetDate = java.time.LocalDate.of(2026, 9, 1),
        )
        coEvery { accountRepository.findById(acc.id) } returns acc
        val captured = slot<Account>()
        coEvery { accountRepository.update(capture(captured)) } answers { firstArg() }

        val result = service.clearSavingsGoal(
            com.openbank.account.application.port.`in`.ClearSavingsGoalCommand(
                accountId = acc.id,
                requestedBy = UUID.randomUUID(),
            ),
        )

        assertThat(captured.captured.goalName).isNull()
        assertThat(captured.captured.goalTargetMinorUnits).isNull()
        assertThat(captured.captured.goalTargetDate).isNull()
        assertThat(result.goalTargetMinorUnits).isNull()
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
