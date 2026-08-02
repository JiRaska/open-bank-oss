// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.DelegatedAccessGrant
import com.openbank.account.domain.model.SavingsDelegationIntent
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The savings-goal delegation guard (ADR-0232 D3): account owner OR an ACTIVE
 * in-window SAVINGS_GOAL grant. Separate from AuthorizationService for the same
 * reason the projection is additive-only — the savings guard can never widen or
 * narrow what the account guard already decided.
 */
@ApplicationScoped
class SavingsGoalDelegationGuard(
    private val accountRepository: AccountRepository,
    private val projectionRepository: DelegationProjectionRepository,
    private val clock: Clock,
) {

    /**
     * A SAVINGS_GOAL grant is keyed on the OWNING ACCOUNT's id (a savings goal is account
     * metadata, ADR-0153), so a grant naming a stranger's account reaches this guard exactly as
     * it reached the account guard — and `SAVINGS_WITHDRAW` moves money. The issuer must
     * therefore own the account, same rule and same reason as AuthorizationService.
     */
    suspend fun isAuthorized(accountId: UUID, partyId: UUID, intent: SavingsDelegationIntent): Boolean {
        val account = accountRepository.findById(accountId) ?: return false
        if (account.partyId == partyId) return true
        val now = OffsetDateTime.now(clock)
        return projectionRepository
            .findActiveByAccountPartyAndType(accountId, partyId, DelegatedAccessGrant.RESOURCE_TYPE_SAVINGS_GOAL)
            .any { it.issuedBy(account.partyId) && it.isActiveOn(now) && it.satisfiesSavings(intent) }
    }
}
