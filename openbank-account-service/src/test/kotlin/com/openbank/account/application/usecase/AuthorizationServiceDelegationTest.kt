// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountAuthorizationRepository
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AuthorizationRole
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class AuthorizationServiceDelegationTest {

    private val accountRepository: AccountRepository = mockk()
    private val authorizationRepository: AccountAuthorizationRepository = mockk()
    private val projectionRepository: DelegationProjectionRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: AuthorizationService

    private val accountId: UUID = UUID.randomUUID()
    private val owner: UUID = UUID.randomUUID()
    private val delegate: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        service = AuthorizationService(accountRepository, authorizationRepository, projectionRepository, clock)
        coEvery { accountRepository.findById(accountId) } returns account()
        coEvery { authorizationRepository.findActiveByAccountAndParty(any(), any()) } returns emptyList()
    }

    private fun account(): Account {
        val acc = mockk<Account>()
        io.mockk.every { acc.partyId } returns owner
        return acc
    }

    private fun grant(
        capabilities: Set<String>,
        validTo: OffsetDateTime? = now.plusDays(30),
        perTxAmount: String? = null,
        grantorPartyId: UUID = owner,
    ) = DelegatedAccessGrant(
        id = UUID.randomUUID(),
        accountId = accountId,
        grantorPartyId = grantorPartyId,
        granteePartyId = delegate,
        capabilities = capabilities,
        perTransactionLimitAmount = perTxAmount?.toBigDecimal(),
        perTransactionLimitCurrency = perTxAmount?.let { "CZK" },
        validFrom = now.minusDays(1),
        validTo = validTo,
    )

    @Test
    fun `owner passes with an empty projection`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, owner) } returns emptyList()
        assertThat(service.isAuthorized(accountId, owner, AuthorizationRole.FULL_ACCESS)).isTrue()
    }

    @Test
    fun `delegate with ACTIVE read capability passes READ_ONLY`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_READ_BALANCES")))
        assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.READ_ONLY)).isTrue()
    }

    @Test
    fun `read grant does not satisfy PAYMENT_ONLY`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_READ_BALANCES")))
        assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.PAYMENT_ONLY)).isFalse()
    }

    @Test
    fun `expired grant denies`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_READ_BALANCES"), validTo = now.minusDays(1)))
        assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.READ_ONLY)).isFalse()
    }

    @Test
    fun `closed row denies`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_READ_BALANCES")).copy(active = false))
        assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.READ_ONLY)).isFalse()
    }

    @Test
    fun `FULL_ACCESS requires all three capabilities`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_READ_BALANCES", "ACCOUNT_INITIATE_PAYMENT")))
        assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.FULL_ACCESS)).isFalse()
    }

    @Test
    fun `amount within per-transaction ceiling passes, above it fails`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_INITIATE_PAYMENT"), perTxAmount = "5000"))
        assertThat(
            service.isAuthorizedForAmount(
                accountId,
                delegate,
                AuthorizationRole.PAYMENT_ONLY,
                Money.of("4999".toBigDecimal(), "CZK"),
            ),
        ).isTrue()
        assertThat(
            service.isAuthorizedForAmount(
                accountId,
                delegate,
                AuthorizationRole.PAYMENT_ONLY,
                Money.of("5001".toBigDecimal(), "CZK"),
            ),
        ).isFalse()
    }

    @Test
    fun `currency mismatch against the ceiling fails closed`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_INITIATE_PAYMENT"), perTxAmount = "5000"))
        assertThat(
            service.isAuthorizedForAmount(
                accountId,
                delegate,
                AuthorizationRole.PAYMENT_ONLY,
                Money.of("10".toBigDecimal(), "EUR"),
            ),
        ).isFalse()
    }

    // --- the grant must have been issued by the account's OWNER -------------------------------
    // Before this, the guard matched on (accountId, granteePartyId) alone, so a projection row is
    // authority in itself: a grant naming somebody else's account was enforced against that
    // account. Two colluding parties could therefore mint payment rights over a stranger's money
    // with nothing but their own valid SCA.

    @Test
    fun `a grant issued by someone who does not own the account is denied`(): Unit = runBlocking {
        val stranger = UUID.randomUUID()
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_READ_BALANCES"), grantorPartyId = stranger))

        assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.READ_ONLY)).isFalse()
    }

    @Test
    fun `a payment grant issued by a non-owner is denied on the amount path too`(): Unit = runBlocking {
        val stranger = UUID.randomUUID()
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_INITIATE_PAYMENT"), perTxAmount = "5000", grantorPartyId = stranger))

        // The amount path is a separate branch of the guard and was separately vulnerable — this
        // is the one that reaches the money.
        assertThat(
            service.isAuthorizedForAmount(
                accountId,
                delegate,
                AuthorizationRole.PAYMENT_ONLY,
                Money.of("100".toBigDecimal(), "CZK"),
            ),
        ).isFalse()
    }

    @Test
    fun `a grant from the owner still passes, on both paths`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(setOf("ACCOUNT_INITIATE_PAYMENT"), perTxAmount = "5000"))

        assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.PAYMENT_ONLY)).isTrue()
        assertThat(
            service.isAuthorizedForAmount(
                accountId,
                delegate,
                AuthorizationRole.PAYMENT_ONLY,
                Money.of("100".toBigDecimal(), "CZK"),
            ),
        ).isTrue()
    }

    @Test
    fun `a valid grant next to a forged one still passes, and the forged one is not what carried it`(): Unit =
        runBlocking {
            val stranger = UUID.randomUUID()
            coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns listOf(
                grant(setOf("ACCOUNT_INITIATE_PAYMENT"), grantorPartyId = stranger),
                grant(setOf("ACCOUNT_READ_BALANCES")),
            )

            assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.READ_ONLY)).isTrue()
            // The forged row is the only one carrying a payment capability.
            assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.PAYMENT_ONLY)).isFalse()
        }

    @Test
    fun `no grant at all denies`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns emptyList()
        assertThat(service.isAuthorized(accountId, delegate, AuthorizationRole.READ_ONLY)).isFalse()
    }
}
