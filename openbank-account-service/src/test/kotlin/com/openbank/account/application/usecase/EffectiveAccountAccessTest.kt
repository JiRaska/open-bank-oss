// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountAuthorizationRepository
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountAccessSource
import com.openbank.account.domain.model.AccountAuthorization
import com.openbank.account.domain.model.AuthorizationRole
import com.openbank.account.domain.model.AuthorizationStatus
import com.openbank.account.domain.model.DelegatedAccessGrant
import com.openbank.libs.domain.money.Money
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `effectiveAccess` — the owner's transparency view (ADR-0232).
 *
 * Every case here is one where the view could plausibly lie to the owner, because that is the only
 * way this endpoint can fail: it changes nothing, so a bug here is always "the owner was told the
 * wrong thing about who can spend their money".
 *
 * The load-bearing one is [both stores are reported]. Two independent stores authorise debits, and
 * a view that showed only the customer's own delegations would read as reassuring while a bank
 * mandate could still empty the account.
 */
class EffectiveAccountAccessTest {

    private val accountRepository: AccountRepository = mockk()
    private val authorizationRepository: AccountAuthorizationRepository = mockk()
    private val projectionRepository: DelegationProjectionRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: AuthorizationService

    private val accountId: UUID = UUID.randomUUID()
    private val owner: UUID = UUID.randomUUID()
    private val delegate: UUID = UUID.randomUUID()
    private val signatory: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        service = AuthorizationService(
            accountRepository,
            authorizationRepository,
            projectionRepository,
            mockk(relaxed = true),
            clock,
        )
        coEvery { accountRepository.findById(accountId) } returns account()
        coEvery { authorizationRepository.findByAccountId(accountId) } returns emptyList()
        coEvery { projectionRepository.findActiveByAccount(accountId) } returns emptyList()
    }

    private fun account(): Account = mockk<Account>().also { io.mockk.every { it.partyId } returns owner }

    private fun grant(
        grantee: UUID = delegate,
        capabilities: Set<String> = setOf(DelegatedAccessGrant.CAP_INITIATE_PAYMENT),
        grantorPartyId: UUID = owner,
        validFrom: OffsetDateTime = now.minusDays(1),
        validTo: OffsetDateTime? = now.plusDays(30),
        active: Boolean = true,
    ) = DelegatedAccessGrant(
        id = UUID.randomUUID(),
        accountId = accountId,
        grantorPartyId = grantorPartyId,
        granteePartyId = grantee,
        capabilities = capabilities,
        validFrom = validFrom,
        validTo = validTo,
        active = active,
    )

    private fun mandate(
        role: AuthorizationRole = AuthorizationRole.PAYMENT_ONLY,
        transactionLimit: Money? = null,
        status: AuthorizationStatus = AuthorizationStatus.ACTIVE,
        validTo: LocalDate? = null,
    ) = AccountAuthorization(
        accountId = accountId,
        partyId = signatory,
        role = role,
        dailyLimit = null,
        transactionLimit = transactionLimit,
        validFrom = LocalDate.of(2026, 1, 1),
        validTo = validTo,
        status = status,
        grantedBy = owner,
        grantedAt = Instant.now(clock),
    )

    @Test
    fun `the owner is always present and can always pay`(): Unit = runBlocking {
        val entries = service.effectiveAccess(accountId)
        assertThat(entries).hasSize(1)
        assertThat(entries.single().source).isEqualTo(AccountAccessSource.OWNER)
        assertThat(entries.single().partyId).isEqualTo(owner)
        assertThat(entries.single().canInitiatePayments).isTrue()
    }

    @Test
    fun `both stores are reported`(): Unit = runBlocking {
        coEvery { authorizationRepository.findByAccountId(accountId) } returns listOf(mandate())
        coEvery { projectionRepository.findActiveByAccount(accountId) } returns listOf(grant())

        val entries = service.effectiveAccess(accountId)

        // The whole point: a view built from one store would still look plausible here.
        assertThat(entries.map { it.source }).containsExactlyInAnyOrder(
            AccountAccessSource.OWNER,
            AccountAccessSource.BANK_MANDATE,
            AccountAccessSource.CUSTOMER_DELEGATION,
        )
        assertThat(entries.filter { it.canInitiatePayments }.map { it.partyId })
            .containsExactlyInAnyOrder(owner, signatory, delegate)
    }

    @Test
    fun `a revoked mandate is not shown`(): Unit = runBlocking {
        coEvery { authorizationRepository.findByAccountId(accountId) } returns
            listOf(mandate(status = AuthorizationStatus.REVOKED))
        assertThat(service.effectiveAccess(accountId).map { it.source })
            .containsExactly(AccountAccessSource.OWNER)
    }

    @Test
    fun `an expired mandate is not shown`(): Unit = runBlocking {
        coEvery { authorizationRepository.findByAccountId(accountId) } returns
            listOf(mandate(validTo = LocalDate.of(2026, 7, 1)))
        assertThat(service.effectiveAccess(accountId).map { it.source })
            .containsExactly(AccountAccessSource.OWNER)
    }

    @Test
    fun `an inactive delegation is not shown`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccount(accountId) } returns listOf(grant(active = false))
        assertThat(service.effectiveAccess(accountId).map { it.source })
            .containsExactly(AccountAccessSource.OWNER)
    }

    @Test
    fun `a grant issued by someone who does not own the account is not shown`(): Unit = runBlocking {
        // Without the issuedBy check a grant naming a stranger's account would appear in this
        // owner's view — and, worse, read as access the owner had given.
        coEvery { projectionRepository.findActiveByAccount(accountId) } returns
            listOf(grant(grantorPartyId = UUID.randomUUID()))
        assertThat(service.effectiveAccess(accountId).map { it.source })
            .containsExactly(AccountAccessSource.OWNER)
    }

    @Test
    fun `a read-only mandate cannot pay`(): Unit = runBlocking {
        coEvery { authorizationRepository.findByAccountId(accountId) } returns
            listOf(mandate(role = AuthorizationRole.READ_ONLY))
        val entry = service.effectiveAccess(accountId).single { it.source == AccountAccessSource.BANK_MANDATE }
        assertThat(entry.canInitiatePayments).isFalse()
    }

    @Test
    fun `a data-only delegation cannot pay`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccount(accountId) } returns
            listOf(grant(capabilities = setOf(DelegatedAccessGrant.CAP_READ_BALANCES)))
        val entry = service.effectiveAccess(accountId)
            .single { it.source == AccountAccessSource.CUSTOMER_DELEGATION }
        assertThat(entry.canInitiatePayments).isFalse()
    }

    @Test
    fun `a mandate limit is carried so the owner sees the ceiling`(): Unit = runBlocking {
        coEvery { authorizationRepository.findByAccountId(accountId) } returns
            listOf(mandate(transactionLimit = Money.of("1000.00", "CZK")))
        val entry = service.effectiveAccess(accountId).single { it.source == AccountAccessSource.BANK_MANDATE }
        assertThat(entry.perTransactionLimit).isEqualByComparingTo("1000.00")
        assertThat(entry.perTransactionLimitCurrency).isEqualTo("CZK")
    }

    @Test
    fun `an unknown account is an empty list, not an exception`(): Unit = runBlocking {
        val unknown = UUID.randomUUID()
        coEvery { accountRepository.findById(unknown) } returns null
        // 404-vs-200 here would let any caller probe which account ids exist.
        assertThat(service.effectiveAccess(unknown)).isEmpty()
    }
}
