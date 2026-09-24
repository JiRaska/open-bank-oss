// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.onboarding

import com.openbank.account.application.port.`in`.AccountUseCase
import com.openbank.account.application.port.`in`.OpenAccountCommand
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.libs.domain.money.CurrencyCode
import java.util.UUID

/**
 * The one definition of "open a business party's onboarding current account", shared by the
 * PARTY_CREATED / party-ACTIVE consumer path and the business-account catch-up job, so the paths
 * cannot drift apart. Opens ONE `PENDING_ACTIVATION` CURRENT account on the business product,
 * unless the party already has a CURRENT account. The idempotency key
 * `onboarding-business-account-<partyId>` makes two racing paths (or a replay) resolve to the same
 * account.
 */
class BusinessOnboardingAccount(
    private val accountRepository: AccountRepository,
    private val accountUseCase: AccountUseCase,
    private val businessProductId: UUID,
    private val businessCurrency: String,
    private val systemActorId: UUID,
) {
    /** Returns true when this call opened the account, false when the party already had one. */
    suspend fun openIfMissing(partyId: UUID, legalName: String): Boolean {
        val existingTypes = accountRepository.findByPartyId(partyId, PAGE, null).map { it.accountType }.toSet()
        if (AccountType.CURRENT in existingTypes) return false
        accountUseCase.openAccount(
            OpenAccountCommand(
                idempotencyKey = idempotencyKey(partyId),
                partyId = partyId,
                productId = businessProductId,
                accountType = AccountType.CURRENT,
                currency = CurrencyCode.of(businessCurrency),
                requestedBy = systemActorId,
                legalName = legalName,
                initialStatus = AccountStatus.PENDING_ACTIVATION,
            ),
        )
        return true
    }

    companion object {
        const val PAGE = 50
        val BUSINESS_PARTY_TYPES = setOf("COMPANY", "SOLE_TRADER")

        fun idempotencyKey(partyId: UUID) = "onboarding-business-account-$partyId"
    }
}
