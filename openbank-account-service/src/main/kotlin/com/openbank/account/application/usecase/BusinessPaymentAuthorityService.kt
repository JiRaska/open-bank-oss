// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.PartyMandateProjectionRepository
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

enum class BusinessPaymentAuthorityOutcome { SOLE, APPROVAL_REQUIRED, NO_MANDATE, ACCOUNT_NOT_FOUND }

data class BusinessPaymentAuthorityDecision(
    val outcome: BusinessPaymentAuthorityOutcome,
    val ownerPartyId: UUID? = null,
) {
    val authorized: Boolean get() = outcome == BusinessPaymentAuthorityOutcome.SOLE
}

/**
 * A direct business debit has only one human SCA decision. It is therefore available only when
 * the authenticated human has an exact active SOLE/one-signature mandate over the account owner.
 * Joint and unknown representation require an operation-specific approval snapshot, not a guess.
 */
@ApplicationScoped
class BusinessPaymentAuthorityService(
    private val accounts: AccountRepository,
    private val mandates: PartyMandateProjectionRepository,
) {
    suspend fun decide(accountId: UUID, actorPartyId: UUID): BusinessPaymentAuthorityDecision {
        val owner = accounts.findById(accountId)?.partyId
            ?: return BusinessPaymentAuthorityDecision(BusinessPaymentAuthorityOutcome.ACCOUNT_NOT_FOUND)
        // This endpoint is for a human acting for a distinct legal person, never for a direct
        // individual owner. The ordinary personal-account path remains unchanged.
        if (owner == actorPartyId) return BusinessPaymentAuthorityDecision(BusinessPaymentAuthorityOutcome.NO_MANDATE)
        val active = mandates.findActive(owner, actorPartyId)
        val outcome = when {
            active.isEmpty() -> BusinessPaymentAuthorityOutcome.NO_MANDATE
            active.any { !it.permitsSoleDecision() } -> BusinessPaymentAuthorityOutcome.APPROVAL_REQUIRED
            else -> BusinessPaymentAuthorityOutcome.SOLE
        }
        return BusinessPaymentAuthorityDecision(outcome, owner)
    }
}
