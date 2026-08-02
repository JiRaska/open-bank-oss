// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.GrantAuthorizationCommand
import com.openbank.account.application.port.`in`.ListAuthorizationsQuery
import com.openbank.account.application.port.`in`.RevokeAuthorizationCommand
import com.openbank.account.application.port.out.AccountAuthorizationRepository
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountAuthorization
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.AuthorizationRole
import com.openbank.account.domain.model.AuthorizationStatus
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class AuthorizationServiceTest {

    private val fixedInstant = Instant.parse("2024-01-15T12:00:00Z")

    private lateinit var accountRepository: AccountRepository
    private lateinit var authorizationRepository: AccountAuthorizationRepository
    private lateinit var delegationProjectionRepository: DelegationProjectionRepository
    private lateinit var service: AuthorizationService

    @BeforeEach
    fun setUp() {
        accountRepository = mockk()
        authorizationRepository = mockk()
        delegationProjectionRepository = mockk()
        coEvery { delegationProjectionRepository.findActiveByAccountAndParty(any(), any()) } returns emptyList()
        service = AuthorizationService(
            accountRepository,
            authorizationRepository,
            delegationProjectionRepository,
            Clock.fixed(fixedInstant, ZoneOffset.UTC),
        )
    }

    // ── Grant ─────────────────────────────────────────────────────────────────

    @Test
    fun `grantAuthorization persists an ACTIVE authorization stamped with the clock`(): Unit = runBlocking {
        val acc = account()
        val command = grantCommand(acc.id)
        coEvery { accountRepository.findById(acc.id) } returns acc
        val saved = slot<AccountAuthorization>()
        coEvery { authorizationRepository.save(capture(saved)) } answers { firstArg() }

        val result = service.grantAuthorization(command)

        assertThat(result.status).isEqualTo(AuthorizationStatus.ACTIVE)
        assertThat(saved.captured.accountId).isEqualTo(command.accountId)
        assertThat(saved.captured.partyId).isEqualTo(command.partyId)
        assertThat(saved.captured.role).isEqualTo(AuthorizationRole.PAYMENT_ONLY)
        assertThat(saved.captured.dailyLimit).isEqualTo(command.dailyLimit)
        assertThat(saved.captured.transactionLimit).isEqualTo(command.transactionLimit)
        assertThat(saved.captured.validFrom).isEqualTo(command.validFrom)
        assertThat(saved.captured.validTo).isEqualTo(command.validTo)
        assertThat(saved.captured.grantedBy).isEqualTo(command.grantedBy)
        assertThat(saved.captured.grantedAt).isEqualTo(fixedInstant)
        assertThat(saved.captured.revokedAt).isNull()
    }

    @Test
    fun `grantAuthorization throws AccountNotFoundException for an unknown account`() {
        val accountId = UUID.randomUUID()
        coEvery { accountRepository.findById(accountId) } returns null

        assertThatThrownBy { runBlocking { service.grantAuthorization(grantCommand(accountId)) } }
            .isInstanceOf(AccountNotFoundException::class.java)
            .hasMessageContaining(accountId.toString())

        coVerify(exactly = 0) { authorizationRepository.save(any()) }
    }

    // ── Revoke ────────────────────────────────────────────────────────────────

    @Test
    fun `revokeAuthorization marks the grant REVOKED with who, why and when`(): Unit = runBlocking {
        val acc = account()
        val auth = authorization(accountId = acc.id)
        val revokedBy = UUID.randomUUID()
        coEvery { authorizationRepository.findById(auth.id) } returns auth
        val saved = slot<AccountAuthorization>()
        coEvery { authorizationRepository.save(capture(saved)) } answers { firstArg() }

        val result = service.revokeAuthorization(
            RevokeAuthorizationCommand(
                accountId = acc.id,
                authorizationId = auth.id,
                revokedBy = revokedBy,
                reason = "mandate withdrawn",
            ),
        )

        assertThat(result.status).isEqualTo(AuthorizationStatus.REVOKED)
        assertThat(saved.captured.revokedBy).isEqualTo(revokedBy)
        assertThat(saved.captured.revokedReason).isEqualTo("mandate withdrawn")
        assertThat(saved.captured.revokedAt).isEqualTo(fixedInstant)
    }

    @Test
    fun `revokeAuthorization throws AuthorizationNotFoundException for an unknown grant`() {
        val authorizationId = UUID.randomUUID()
        coEvery { authorizationRepository.findById(authorizationId) } returns null

        assertThatThrownBy {
            runBlocking {
                service.revokeAuthorization(
                    RevokeAuthorizationCommand(
                        accountId = UUID.randomUUID(),
                        authorizationId = authorizationId,
                        revokedBy = UUID.randomUUID(),
                        reason = "mandate withdrawn",
                    ),
                )
            }
        }.isInstanceOf(AuthorizationNotFoundException::class.java)
    }

    @Test
    fun `revokeAuthorization refuses a grant that belongs to a different account`() {
        val auth = authorization(accountId = UUID.randomUUID())
        val otherAccountId = UUID.randomUUID()
        coEvery { authorizationRepository.findById(auth.id) } returns auth

        assertThatThrownBy {
            runBlocking {
                service.revokeAuthorization(
                    RevokeAuthorizationCommand(
                        accountId = otherAccountId,
                        authorizationId = auth.id,
                        revokedBy = UUID.randomUUID(),
                        reason = "mandate withdrawn",
                    ),
                )
            }
        }.isInstanceOf(AuthorizationNotOnAccountException::class.java)
            .hasMessageContaining(otherAccountId.toString())

        coVerify(exactly = 0) { authorizationRepository.save(any()) }
    }

    // ── List / isAuthorized ───────────────────────────────────────────────────

    @Test
    fun `listAuthorizations returns the repository's grants for the account`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val grants = listOf(authorization(accountId = accountId), authorization(accountId = accountId))
        coEvery { authorizationRepository.findByAccountId(accountId) } returns grants

        assertThat(service.listAuthorizations(ListAuthorizationsQuery(accountId))).isEqualTo(grants)
    }

    @Test
    fun `isAuthorized returns false when the account does not exist`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        coEvery { accountRepository.findById(accountId) } returns null

        val authorized = service.isAuthorized(accountId, UUID.randomUUID(), AuthorizationRole.READ_ONLY)

        assertThat(authorized).isFalse()
    }

    @Test
    fun `isAuthorized short-circuits to true for the account owner`(): Unit = runBlocking {
        val acc = account()
        coEvery { accountRepository.findById(acc.id) } returns acc

        val authorized = service.isAuthorized(acc.id, acc.partyId, AuthorizationRole.PAYMENT_ONLY)

        assertThat(authorized).isTrue()
        coVerify(exactly = 0) { authorizationRepository.findActiveByAccountAndParty(any(), any()) }
    }

    @Test
    fun `isAuthorized accepts an active grant with the exact requested role`(): Unit = runBlocking {
        val acc = account()
        val partyId = UUID.randomUUID()
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { authorizationRepository.findActiveByAccountAndParty(acc.id, partyId) } returns
            listOf(authorization(accountId = acc.id, partyId = partyId, role = AuthorizationRole.PAYMENT_ONLY))

        assertThat(service.isAuthorized(acc.id, partyId, AuthorizationRole.PAYMENT_ONLY)).isTrue()
    }

    @Test
    fun `isAuthorized accepts FULL_ACCESS as a superset of any requested role`(): Unit = runBlocking {
        val acc = account()
        val partyId = UUID.randomUUID()
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { authorizationRepository.findActiveByAccountAndParty(acc.id, partyId) } returns
            listOf(authorization(accountId = acc.id, partyId = partyId, role = AuthorizationRole.FULL_ACCESS))

        assertThat(service.isAuthorized(acc.id, partyId, AuthorizationRole.CARD_HOLDER)).isTrue()
    }

    @Test
    fun `isAuthorized rejects when no active grant matches the requested role`(): Unit = runBlocking {
        val acc = account()
        val partyId = UUID.randomUUID()
        coEvery { accountRepository.findById(acc.id) } returns acc
        coEvery { authorizationRepository.findActiveByAccountAndParty(acc.id, partyId) } returns
            listOf(authorization(accountId = acc.id, partyId = partyId, role = AuthorizationRole.READ_ONLY))

        assertThat(service.isAuthorized(acc.id, partyId, AuthorizationRole.PAYMENT_ONLY)).isFalse()
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun account(partyId: UUID = UUID.randomUUID()) = Account(
        id = UUID.randomUUID(),
        accountNumber = Iban.of("CZ6508000000192000145399"),
        accountType = AccountType.CURRENT,
        partyId = partyId,
        productId = UUID.randomUUID(),
        currency = CurrencyCode.CZK,
        status = AccountStatus.ACTIVE,
        openedAt = Instant.parse("2023-06-01T00:00:00Z"),
        closedAt = null,
        version = 0L,
    )

    private fun grantCommand(accountId: UUID) = GrantAuthorizationCommand(
        accountId = accountId,
        partyId = UUID.randomUUID(),
        role = AuthorizationRole.PAYMENT_ONLY,
        dailyLimit = Money.of(BigDecimal("10000.00"), "CZK"),
        transactionLimit = Money.of(BigDecimal("2500.00"), "CZK"),
        validFrom = LocalDate.of(2024, 1, 1),
        validTo = LocalDate.of(2024, 12, 31),
        grantedBy = UUID.randomUUID(),
    )

    private fun authorization(
        accountId: UUID,
        partyId: UUID = UUID.randomUUID(),
        role: AuthorizationRole = AuthorizationRole.PAYMENT_ONLY,
    ) = AccountAuthorization(
        accountId = accountId,
        partyId = partyId,
        role = role,
        dailyLimit = null,
        transactionLimit = null,
        validFrom = LocalDate.of(2024, 1, 1),
        validTo = null,
        grantedBy = UUID.randomUUID(),
        grantedAt = Instant.parse("2024-01-01T00:00:00Z"),
    )
}
