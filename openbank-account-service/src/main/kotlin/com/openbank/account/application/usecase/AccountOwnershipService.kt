// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.VerifyAccountOwnershipQuery
import com.openbank.account.application.port.`in`.VerifyAccountOwnershipUseCase
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.domain.model.OwnershipVerdict
import com.openbank.libs.domain.account.Iban
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class AccountOwnershipService(private val accountRepository: AccountRepository) : VerifyAccountOwnershipUseCase {
    override suspend fun verifyOwnership(query: VerifyAccountOwnershipQuery): OwnershipVerdict =
        OwnershipVerdict.of(accountRepository.findByIban(Iban.of(query.iban)), query.partyId)
}
