// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.AddPocketCommand
import com.openbank.account.application.port.`in`.CloseAccountCommand
import com.openbank.account.application.port.`in`.ClosePocketCommand
import com.openbank.account.application.port.`in`.FreezeAccountCommand
import com.openbank.account.application.port.`in`.GetAccountByIbanQuery
import com.openbank.account.application.port.`in`.GetAccountQuery
import com.openbank.account.application.port.`in`.ListAccountsQuery
import com.openbank.account.application.port.`in`.ListPocketsQuery
import com.openbank.account.application.port.`in`.ResolvePocketQuery
import com.openbank.account.application.port.`in`.SearchAccountsQuery
import com.openbank.account.application.port.`in`.UnfreezeAccountCommand
import com.openbank.account.application.port.out.AccountEventPublisher
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.BalanceQueryPort
import com.openbank.account.application.port.out.BalanceView
import com.openbank.account.application.port.out.CurrencyPocketRepository
import com.openbank.account.application.port.out.NotificationRequestPort
import com.openbank.account.application.port.out.ProductCatalogPort
import com.openbank.account.domain.event.AccountClosedEvent
import com.openbank.account.domain.event.AccountStatusChangedEvent
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.CurrencyPocket
import com.openbank.account.domain.model.MissingPocketPolicy
import com.openbank.account.domain.model.PocketResolution
import com.openbank.account.domain.model.PocketStatus
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Lifecycle, lookup and pocket behavior of [AccountService] — the paths not covered by
 * [AccountServiceTest] (which owns opening, search/pagination, sanctions and savings-goal).
 */
class AccountServiceLifecycleTest {

    private val fixedInstant = Instant.parse("2024-01-15T12:00:00Z")

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
                Clock.fixed(fixedInstant, ZoneOffset.UTC),
                notificationRequestPort,
            )
    }

    // ── Activation (ADR-0267) ─────────────────────────────────────────────────

    @Test
    fun `activateAccount transitions PENDING_ACTIVATION to ACTIVE and publishes the exact transition`(): Unit =
        runBlocking {
            val acc = account(status = AccountStatus.PENDING_ACTIVATION)
            coEvery { accountRepository.findById(acc.id) } returns acc
            coEvery { accountRepository.update(any()) } answers { firstArg() }
            val event = slot<Any>()
            coEvery {
                eventPublisher.publish("openbank.accounts.account.status-changed", acc.id.toString(), capture(event))
            } returns Unit

            val result = service.activateAccount(acc.id)

            assertThat(result.status).isEqualTo(AccountStatus.ACTIVE)
            val statusChanged = event.captured as AccountStatusChangedEvent
            assertThat(statusChanged.aggregateId).isEqualTo(acc.id)
            assertThat(statusChanged.previousStatus).isEqualTo(AccountStatus.PENDING_ACTIVATION)
            assertThat(statusChanged.newStatus).isEqualTo(AccountStatus.ACTIVE)
            assertThat(statusChanged.occurredAt).isEqualTo(fixedInstant)
        }

    @Test
    fun `activateAccount is idempotent for an already-ACTIVE account`(): Unit = runBlocking {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc

        val result = service.activateAccount(acc.id)

        assertThat(result).isEqualTo(acc)
        coVerify(exactly = 0) {
            accountRepository.update(any())
            eventPublisher.publish(any(), any(), any())
        }
    }

    @Test
    fun `activateAccount rejects an account that is not PENDING_ACTIVATION`() {
        val acc = account(status = AccountStatus.FROZEN)
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy { runBlocking { service.activateAccount(acc.id) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot activate")

        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    // ── Freeze / unfreeze ─────────────────────────────────────────────────────

    @Test
    fun `freezeAccount moves ACTIVE to FROZEN and publishes the operator reason`(): Unit = runBlocking {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { accountRepository.update(any()) } answers { firstArg() }
        val event = slot<Any>()
        coEvery {
            eventPublisher.publish("openbank.accounts.account.status-changed", acc.id.toString(), capture(event))
        } returns Unit

        val result = service.freezeAccount(
            FreezeAccountCommand(accountId = acc.id, reason = "AML alert", requestedBy = UUID.randomUUID()),
        )

        assertThat(result.status).isEqualTo(AccountStatus.FROZEN)
        val statusChanged = event.captured as AccountStatusChangedEvent
        assertThat(statusChanged.previousStatus).isEqualTo(AccountStatus.ACTIVE)
        assertThat(statusChanged.newStatus).isEqualTo(AccountStatus.FROZEN)
        assertThat(statusChanged.reason).isEqualTo("AML alert")
    }

    @Test
    fun `freezeAccount rejects a CLOSED account without updating it`() {
        val acc = account(status = AccountStatus.CLOSED)
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy {
            runBlocking {
                service.freezeAccount(
                    FreezeAccountCommand(accountId = acc.id, reason = "AML alert", requestedBy = UUID.randomUUID()),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot freeze")

        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    @Test
    fun `freezeAccount rejects an already-FROZEN account without updating it`() {
        val acc = account(status = AccountStatus.FROZEN)
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy {
            runBlocking {
                service.freezeAccount(
                    FreezeAccountCommand(
                        accountId = acc.id,
                        reason = "duplicate freeze",
                        requestedBy = UUID.randomUUID(),
                    ),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot freeze")

        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    @Test
    fun `unfreezeAccount rejects an ACTIVE (non-frozen) account without updating it`() {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy {
            runBlocking {
                service.unfreezeAccount(
                    UnfreezeAccountCommand(accountId = acc.id, reason = "not frozen", requestedBy = UUID.randomUUID()),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot unfreeze")

        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    @Test
    fun `unfreezeAccount returns a FROZEN account to ACTIVE and publishes the transition`(): Unit = runBlocking {
        val acc = account(status = AccountStatus.FROZEN)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { accountRepository.update(any()) } answers { firstArg() }
        val event = slot<Any>()
        coEvery {
            eventPublisher.publish("openbank.accounts.account.status-changed", acc.id.toString(), capture(event))
        } returns Unit

        val result = service.unfreezeAccount(
            UnfreezeAccountCommand(accountId = acc.id, reason = "alert cleared", requestedBy = UUID.randomUUID()),
        )

        assertThat(result.status).isEqualTo(AccountStatus.ACTIVE)
        val statusChanged = event.captured as AccountStatusChangedEvent
        assertThat(statusChanged.previousStatus).isEqualTo(AccountStatus.FROZEN)
        assertThat(statusChanged.newStatus).isEqualTo(AccountStatus.ACTIVE)
        assertThat(statusChanged.reason).isEqualTo("alert cleared")
    }

    @Test
    fun `unfreezeAccount rejects an account that is not FROZEN`() {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy {
            runBlocking {
                service.unfreezeAccount(
                    UnfreezeAccountCommand(
                        accountId = acc.id,
                        reason = "alert cleared",
                        requestedBy = UUID.randomUUID(),
                    ),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot unfreeze")

        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    @Test
    fun `unfreezeAccount throws AccountNotFoundException for an unknown account`() {
        val accountId = UUID.randomUUID()
        coEvery { accountRepository.findById(accountId) } returns null

        assertThatThrownBy {
            runBlocking {
                service.unfreezeAccount(
                    UnfreezeAccountCommand(
                        accountId = accountId,
                        reason = "alert cleared",
                        requestedBy = UUID.randomUUID(),
                    ),
                )
            }
        }.isInstanceOf(AccountNotFoundException::class.java)
            .hasMessageContaining(accountId.toString())

        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    @Test
    fun `closeAccount stamps closedAt from the clock and publishes AccountClosedEvent with the reason`(): Unit =
        runBlocking {
            val acc = account(status = AccountStatus.ACTIVE)
            coEvery { accountRepository.findById(acc.id) } returns acc
            coEvery { balancePort.getByAccount(acc.id) } returns emptyList()
            val updated = slot<Account>()
            coEvery { accountRepository.update(capture(updated)) } answers { firstArg() }
            val event = slot<Any>()
            coEvery {
                eventPublisher.publish("openbank.accounts.account.status-changed", acc.id.toString(), capture(event))
            } returns Unit

            val result = service.closeAccount(
                CloseAccountCommand(
                    accountId = acc.id,
                    reason = "dormant for 10 years",
                    requestedBy = UUID.randomUUID(),
                ),
            )

            assertThat(result.status).isEqualTo(AccountStatus.CLOSED)
            assertThat(updated.captured.closedAt).isEqualTo(fixedInstant)
            val closedEvent = event.captured as AccountClosedEvent
            assertThat(closedEvent.aggregateId).isEqualTo(acc.id)
            assertThat(closedEvent.reason).isEqualTo("dormant for 10 years")
            assertThat(closedEvent.occurredAt).isEqualTo(fixedInstant)
        }

    @Test
    fun `closeAccount rejects an already-CLOSED account without republishing or updating it`() {
        val acc = account(status = AccountStatus.CLOSED)
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy {
            runBlocking {
                service.closeAccount(
                    CloseAccountCommand(
                        accountId = acc.id,
                        reason = "duplicate close",
                        requestedBy = UUID.randomUUID(),
                    ),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot close")

        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    @Test
    fun `closeAccount refuses to close an account that still holds money`() {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { balancePort.getByAccount(acc.id) } returns listOf(
            BalanceView(
                accountId = acc.id,
                currency = "CZK",
                booked = BigDecimal("1250.00"),
                available = BigDecimal("1250.00"),
                reserved = BigDecimal.ZERO,
                pending = BigDecimal.ZERO,
                arrangedOverdraftLimit = BigDecimal.ZERO,
                updatedAt = fixedInstant,
            ),
        )

        assertThatThrownBy {
            runBlocking {
                service.closeAccount(
                    CloseAccountCommand(
                        accountId = acc.id,
                        reason = "customer request",
                        requestedBy = UUID.randomUUID(),
                    ),
                )
            }
        }.isInstanceOf(AccountNotEmptyException::class.java)

        coVerify(exactly = 0) { accountRepository.update(any()) }
        // #4348 hazard: a refused close must never share the accountClosed count with a real one.
        verify(exactly = 0) { metrics.accountClosed(any(), any()) }
    }

    @Test
    fun `closeAccount throws AccountNotFoundException for an unknown account`() {
        val accountId = UUID.randomUUID()
        coEvery { accountRepository.findById(accountId) } returns null

        assertThatThrownBy {
            runBlocking {
                service.closeAccount(
                    CloseAccountCommand(accountId = accountId, reason = null, requestedBy = UUID.randomUUID()),
                )
            }
        }.isInstanceOf(AccountNotFoundException::class.java)
            .hasMessageContaining(accountId.toString())
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    @Test
    fun `getAccount returns the account when it exists`(): Unit = runBlocking {
        val acc = account()
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThat(service.getAccount(GetAccountQuery(acc.id))).isEqualTo(acc)
    }

    @Test
    fun `getAccount throws AccountNotFoundException when it does not`() {
        val accountId = UUID.randomUUID()
        coEvery { accountRepository.findById(accountId) } returns null

        assertThatThrownBy { runBlocking { service.getAccount(GetAccountQuery(accountId)) } }
            .isInstanceOf(AccountNotFoundException::class.java)
    }

    @Test
    fun `getAccountByIban resolves the account through the normalized IBAN`(): Unit = runBlocking {
        val acc = account()
        coEvery { accountRepository.findByIban(Iban.of("CZ6508000000192000145399")) } returns acc

        assertThat(service.getAccountByIban(GetAccountByIbanQuery("CZ6508000000192000145399"))).isEqualTo(acc)
    }

    @Test
    fun `getAccountByIban throws AccountNotFoundException for an unknown IBAN`() {
        coEvery { accountRepository.findByIban(any()) } returns null

        assertThatThrownBy {
            runBlocking { service.getAccountByIban(GetAccountByIbanQuery("CZ6508000000192000145399")) }
        }.isInstanceOf(AccountNotFoundException::class.java)
    }

    @Test
    fun `getBalance reads the balance in the account's own currency`(): Unit = runBlocking {
        val acc = account()
        val view = balanceView(acc.id, "CZK")
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { balancePort.getByAccountAndCurrency(acc.id, "CZK") } returns view

        assertThat(service.getBalance(acc.id)).isEqualTo(view)
    }

    @Test
    fun `getBalance throws AccountNotFoundException when balance-service has no balance`() {
        val acc = account()
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { balancePort.getByAccountAndCurrency(acc.id, "CZK") } returns null

        assertThatThrownBy { runBlocking { service.getBalance(acc.id) } }
            .isInstanceOf(AccountNotFoundException::class.java)
            .hasMessageContaining("Balance not found")
    }

    // ── Pockets (ADR-0024) ────────────────────────────────────────────────────

    @Test
    fun `addPocket saves a secondary ACTIVE pocket and seeds a zero balance`(): Unit = runBlocking {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { pocketRepository.findByAccountIdAndCurrency(acc.id, "EUR") } returns null
        val saved = slot<CurrencyPocket>()
        coEvery { pocketRepository.save(capture(saved)) } answers { firstArg() }
        coEvery { balancePort.initialize(acc.id, "EUR", BigDecimal.ZERO) } returns Unit

        val pocket = service.addPocket(
            AddPocketCommand(accountId = acc.id, currency = CurrencyCode.EUR, requestedBy = UUID.randomUUID()),
        )

        assertThat(pocket.currency).isEqualTo(CurrencyCode.EUR)
        assertThat(saved.captured.isPrimary).isFalse()
        assertThat(saved.captured.status).isEqualTo(PocketStatus.ACTIVE)
        assertThat(saved.captured.openedAt).isEqualTo(fixedInstant)
        coVerify(exactly = 1) { balancePort.initialize(acc.id, "EUR", BigDecimal.ZERO) }
    }

    @Test
    fun `addPocket rejects an account that is not ACTIVE`() {
        val acc = account(status = AccountStatus.FROZEN)
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy {
            runBlocking {
                service.addPocket(
                    AddPocketCommand(accountId = acc.id, currency = CurrencyCode.EUR, requestedBy = UUID.randomUUID()),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("FROZEN")

        coVerify(exactly = 0) { pocketRepository.save(any()) }
    }

    @Test
    fun `addPocket rejects a duplicate currency pocket`() {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { pocketRepository.findByAccountIdAndCurrency(acc.id, "EUR") } returns
            pocket(acc.id, CurrencyCode.EUR)

        assertThatThrownBy {
            runBlocking {
                service.addPocket(
                    AddPocketCommand(accountId = acc.id, currency = CurrencyCode.EUR, requestedBy = UUID.randomUUID()),
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("already exists")

        coVerify(exactly = 0) { pocketRepository.save(any()) }
    }

    @Test
    fun `closePocket closes an existing secondary pocket via the domain transition`(): Unit = runBlocking {
        val acc = account(status = AccountStatus.ACTIVE)
        val existing = pocket(acc.id, CurrencyCode.EUR)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { pocketRepository.findByAccountIdAndCurrency(acc.id, "EUR") } returns existing
        val updated = slot<CurrencyPocket>()
        coEvery { pocketRepository.update(capture(updated)) } answers { firstArg() }

        val result = service.closePocket(
            ClosePocketCommand(accountId = acc.id, currency = CurrencyCode.EUR, requestedBy = UUID.randomUUID()),
        )

        assertThat(result.status).isEqualTo(PocketStatus.CLOSED)
        assertThat(updated.captured.closedAt).isEqualTo(fixedInstant)
        assertThat(updated.captured.version).isEqualTo(existing.version + 1)
    }

    @Test
    fun `closePocket throws when the pocket does not exist on the account`() {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { pocketRepository.findByAccountIdAndCurrency(acc.id, "EUR") } returns null

        assertThatThrownBy {
            runBlocking {
                service.closePocket(
                    ClosePocketCommand(
                        accountId = acc.id,
                        currency = CurrencyCode.EUR,
                        requestedBy = UUID.randomUUID(),
                    ),
                )
            }
        }.isInstanceOf(AccountNotFoundException::class.java)
            .hasMessageContaining("Pocket EUR not found")
    }

    @Test
    fun `listPockets returns the account's pockets`(): Unit = runBlocking {
        val acc = account()
        val pockets = listOf(pocket(acc.id, CurrencyCode.CZK, isPrimary = true), pocket(acc.id, CurrencyCode.EUR))
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { pocketRepository.findByAccountId(acc.id) } returns pockets

        assertThat(service.listPockets(ListPocketsQuery(acc.id))).isEqualTo(pockets)
    }

    @Test
    fun `resolvePocket routes with the account currency as primary`(): Unit = runBlocking {
        val acc = account() // primary currency CZK
        val primary = pocket(acc.id, CurrencyCode.CZK, isPrimary = true)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { pocketRepository.findByAccountId(acc.id) } returns listOf(primary)

        val resolution = service.resolvePocket(
            ResolvePocketQuery(
                accountId = acc.id,
                paymentCurrency = CurrencyCode.USD,
                policy = MissingPocketPolicy.CONVERT_TO_PRIMARY,
            ),
        )

        assertThat(resolution).isInstanceOf(PocketResolution.ConvertToPrimary::class.java)
        val convert = resolution as PocketResolution.ConvertToPrimary
        assertThat(convert.from).isEqualTo(CurrencyCode.USD)
        assertThat(convert.primary).isEqualTo(primary)
    }

    // ── Pagination edges ──────────────────────────────────────────────────────

    @Test
    fun `listAccounts without cursor returns no next cursor for a short page`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val accounts = listOf(account(partyId = partyId))
        coEvery { accountRepository.findByPartyId(partyId, 21, null) } returns accounts

        val page = service.listAccounts(ListAccountsQuery(partyId = partyId))

        assertThat(page.data).isEqualTo(accounts)
        assertThat(page.pagination.hasNextPage).isFalse()
        assertThat(page.pagination.nextCursor).isNull()
    }

    @Test
    fun `searchAccounts caps the requested page size at MAX_SEARCH_LIMIT`(): Unit = runBlocking {
        coEvery { accountRepository.searchByIban("0800", AccountService.MAX_SEARCH_LIMIT + 1, null) } returns
            emptyList()

        val page = service.searchAccounts(SearchAccountsQuery(query = "0800", limit = 500))

        assertThat(page.pagination.limit).isEqualTo(AccountService.MAX_SEARCH_LIMIT)
        coVerify(exactly = 1) { accountRepository.searchByIban("0800", AccountService.MAX_SEARCH_LIMIT + 1, null) }
    }

    @Test
    fun `searchAccounts floors a non-positive requested page size at 1`(): Unit = runBlocking {
        coEvery { accountRepository.searchByIban("0800", 2, null) } returns emptyList()

        val page = service.searchAccounts(SearchAccountsQuery(query = "0800", limit = -5))

        assertThat(page.pagination.limit).isEqualTo(1)
        coVerify(exactly = 1) { accountRepository.searchByIban("0800", 2, null) }
    }

    @Test
    fun `searchAccounts queries at exactly MIN_SEARCH_FRAGMENT length (inclusive boundary)`(): Unit = runBlocking {
        coEvery { accountRepository.searchByIban("CZ", 21, null) } returns emptyList()

        val page = service.searchAccounts(SearchAccountsQuery(query = "cz", limit = 20))

        assertThat(page.data).isEmpty()
        coVerify(exactly = 1) { accountRepository.searchByIban("CZ", 21, null) }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun account(
        id: UUID = UUID.randomUUID(),
        partyId: UUID = UUID.randomUUID(),
        status: AccountStatus = AccountStatus.ACTIVE,
    ) = Account(
        id = id,
        accountNumber = Iban.of("CZ6508000000192000145399"),
        accountType = AccountType.CURRENT,
        partyId = partyId,
        productId = UUID.randomUUID(),
        currency = CurrencyCode.CZK,
        status = status,
        openedAt = Instant.parse("2023-06-01T00:00:00Z"),
        closedAt = null,
        version = 3L,
    )

    private fun pocket(accountId: UUID, currency: CurrencyCode, isPrimary: Boolean = false) = CurrencyPocket(
        id = UUID.randomUUID(),
        accountId = accountId,
        currency = currency,
        isPrimary = isPrimary,
        status = PocketStatus.ACTIVE,
        openedAt = Instant.parse("2023-06-01T00:00:00Z"),
        closedAt = null,
        version = 2L,
    )

    private fun balanceView(accountId: UUID, currency: String) = BalanceView(
        accountId = accountId,
        currency = currency,
        booked = BigDecimal("100.00"),
        available = BigDecimal("90.00"),
        reserved = BigDecimal("10.00"),
        pending = BigDecimal.ZERO,
        arrangedOverdraftLimit = BigDecimal.ZERO,
        updatedAt = Instant.parse("2024-01-15T11:59:00Z"),
    )

    // ── customer lifecycle notifications (#8432) ─────────────────────────────

    /**
     * `ACCOUNT_OPENED`, `ACCOUNT_CLOSED` and `ACCOUNT_FROZEN` have been declared and rendered in
     * notification-service since it was written, and **emitted by nothing** — 12 of its 23
     * templates were in that state. These pin the producers this change adds.
     */
    @Test
    fun `activateAccount tells the customer the account is usable`(): Unit = runBlocking {
        val acc = account(status = AccountStatus.PENDING_ACTIVATION)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { accountRepository.update(any()) } answers { firstArg() }
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

        service.activateAccount(acc.id)

        coVerify(exactly = 1) {
            notificationRequestPort.notifyAccountOpened(acc.partyId, "CZ6508000000192000145399")
        }
    }

    /**
     * The decision worth reviewing: an account is announced when it becomes USABLE, not when its
     * row appears. ADR-0267 opens onboarding accounts PENDING_ACTIVATION, which cannot debit or
     * credit — announcing those would tell a new customer twice about accounts that move no money,
     * then say nothing at the moment they go live.
     */
    @Test
    fun `an already-ACTIVE account is not announced twice`(): Unit = runBlocking {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc

        service.activateAccount(acc.id)

        coVerify(exactly = 0) { notificationRequestPort.notifyAccountOpened(any(), any()) }
    }

    /**
     * The one a customer would want most, and the one never sent: an account freeze is otherwise
     * invisible until they try to pay and fail. The operator's reason is carried because the
     * template renders it.
     */
    @Test
    fun `freezeAccount tells the customer, with the operator's reason`(): Unit = runBlocking {
        val acc = account(status = AccountStatus.ACTIVE)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { accountRepository.update(any()) } answers { firstArg() }
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit

        service.freezeAccount(FreezeAccountCommand(acc.id, "Suspected fraud", UUID.randomUUID()))

        coVerify(exactly = 1) {
            notificationRequestPort.notifyAccountFrozen(
                acc.partyId,
                "CZ6508000000192000145399",
                "Suspected fraud",
            )
        }
    }

    /**
     * A refused transition must not notify. Otherwise merely ASKING to freeze a closed account
     * would tell the customer it was frozen.
     */
    @Test
    fun `a refused freeze notifies nobody`() {
        val acc = account(status = AccountStatus.CLOSED)
        coEvery { accountRepository.findById(acc.id) } returns acc

        assertThatThrownBy {
            runBlocking { service.freezeAccount(FreezeAccountCommand(acc.id, "Suspected fraud", UUID.randomUUID())) }
        }.isInstanceOf(IllegalStateException::class.java)

        coVerify(exactly = 0) { notificationRequestPort.notifyAccountFrozen(any(), any(), any()) }
    }

    /**
     * **The load-bearing one.** Opening, closing and freezing are money-path state changes with
     * events and audit behind them. A broker hiccup must not roll one back, nor turn a completed
     * operation into a 500 a saga retries — so the notification failure is swallowed and the
     * transition stands.
     */
    @Test
    fun `a failing notification does not undo the state change`(): Unit = runBlocking {
        val acc = account(status = AccountStatus.PENDING_ACTIVATION)
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { accountRepository.update(any()) } answers { firstArg() }
        coEvery { eventPublisher.publish(any(), any(), any()) } returns Unit
        coEvery { notificationRequestPort.notifyAccountOpened(any(), any()) } throws
            RuntimeException("broker unreachable")

        val result = service.activateAccount(acc.id)

        assertThat(result.status).isEqualTo(AccountStatus.ACTIVE)
        coVerify(exactly = 1) { accountRepository.update(any()) }
    }
}
