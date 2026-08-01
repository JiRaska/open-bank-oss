// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.AuthorizationUseCase
import com.openbank.account.application.port.`in`.GrantAuthorizationCommand
import com.openbank.account.application.port.`in`.ListAuthorizationsQuery
import com.openbank.account.application.port.`in`.RevokeAuthorizationCommand
import com.openbank.account.application.port.out.AccountAuthorizationRepository
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.AccountAuthorization
import com.openbank.account.domain.model.AuthorizationRole
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class AuthorizationNotFoundException(id: UUID) : RuntimeException("Authorization not found: $id")
class AuthorizationNotOnAccountException(authId: UUID, accountId: UUID) :
    RuntimeException("Authorization $authId does not belong to account $accountId")

@ApplicationScoped
class AuthorizationService(
    private val accountRepository: AccountRepository,
    private val authorizationRepository: AccountAuthorizationRepository,
    private val delegationProjectionRepository: DelegationProjectionRepository,
    private val clock: Clock,
) : AuthorizationUseCase {

    override suspend fun grantAuthorization(command: GrantAuthorizationCommand): AccountAuthorization {
        accountRepository.findById(command.accountId)
            ?: throw AccountNotFoundException("Account not found: ${command.accountId}")

        val auth = AccountAuthorization(
            accountId = command.accountId,
            partyId = command.partyId,
            role = command.role,
            dailyLimit = command.dailyLimit,
            transactionLimit = command.transactionLimit,
            validFrom = command.validFrom,
            validTo = command.validTo,
            grantedBy = command.grantedBy,
            grantedAt = Instant.now(clock),
        )

        return authorizationRepository.save(auth)
    }

    override suspend fun revokeAuthorization(command: RevokeAuthorizationCommand): AccountAuthorization {
        val auth = authorizationRepository.findById(command.authorizationId)
            ?: throw AuthorizationNotFoundException(command.authorizationId)

        if (auth.accountId != command.accountId) {
            throw AuthorizationNotOnAccountException(command.authorizationId, command.accountId)
        }

        return authorizationRepository.save(auth.revoke(command.revokedBy, command.reason, clock))
    }

    override suspend fun listAuthorizations(query: ListAuthorizationsQuery): List<AccountAuthorization> =
        authorizationRepository.findByAccountId(query.accountId)

    override suspend fun isAuthorized(accountId: UUID, partyId: UUID, role: AuthorizationRole): Boolean {
        val account = accountRepository.findById(accountId) ?: return false
        if (account.partyId == partyId) return true
        val active = authorizationRepository.findActiveByAccountAndParty(accountId, partyId)
        if (active.any { it.role == role || it.role == AuthorizationRole.FULL_ACCESS }) return true
        return hasDelegatedAccess(accountId, partyId, role)
    }

    override suspend fun isAuthorizedForAmount(
        accountId: UUID,
        partyId: UUID,
        role: AuthorizationRole,
        amount: com.openbank.libs.domain.money.Money?,
    ): Boolean {
        val account = accountRepository.findById(accountId) ?: return false
        if (account.partyId == partyId) return true
        val active = authorizationRepository.findActiveByAccountAndParty(accountId, partyId)
        if (active.any { it.role == role || it.role == AuthorizationRole.FULL_ACCESS }) return true
        if (amount == null) return hasDelegatedAccess(accountId, partyId, role)
        val now = OffsetDateTime.now(clock)
        return delegationProjectionRepository.findActiveByAccountAndParty(accountId, partyId)
            .filter { it.isActiveOn(now) && it.satisfies(role) }
            .any { it.withinPerTransactionLimit(amount.amount, amount.currency.code) }
    }

    /**
     * ADR-0232 D3: the third disjunct of the guard — an ACTIVE, in-window delegation
     * grant from the local event-fed projection. Runs after ownership and the legacy
     * AccountAuthorization table so the 99% path (owner) and the existing grants keep
     * their exact behavior; delegation only ever ADDS access, never removes it.
     */
    private suspend fun hasDelegatedAccess(accountId: UUID, partyId: UUID, role: AuthorizationRole): Boolean {
        val now = OffsetDateTime.now(clock)
        return delegationProjectionRepository.findActiveByAccountAndParty(accountId, partyId)
            .any { it.isActiveOn(now) && it.satisfies(role) }
    }
}
