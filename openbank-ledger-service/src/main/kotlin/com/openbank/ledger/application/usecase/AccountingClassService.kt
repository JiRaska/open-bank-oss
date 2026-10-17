// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.out.AccountingClassMapRepository
import com.openbank.ledger.domain.model.AccountingClassMapping
import com.openbank.ledger.domain.model.AccountingRole
import com.openbank.ledger.domain.model.requireValid
import jakarta.enterprise.context.ApplicationScoped

/**
 * Resolves (accountingClass, role, currency) to a GL account (ADR-0341 D3). Product services post
 * with the class; this is the only place that knows which account that is.
 *
 * An unmapped triple is a 422, never a fall-back to some default account: posting a product's
 * money to a guessed account is the failure this indirection exists to prevent.
 */
@ApplicationScoped
class AccountingClassService(private val map: AccountingClassMapRepository) {

    suspend fun resolve(accountingClass: String, role: String, currencyCode: String): AccountingClassMapping {
        val parsedRole = AccountingRole.entries.firstOrNull { it.name == role }
        requireValid(parsedRole != null) {
            "unknown accountingRole '$role'; expected one of ${AccountingRole.entries.joinToString()}"
        }
        val mapping = map.find(accountingClass, checkNotNull(parsedRole), currencyCode)
        requireValid(mapping != null) {
            "no GL account is mapped for accountingClass=$accountingClass role=$role currency=$currencyCode"
        }
        return checkNotNull(mapping)
    }

    suspend fun list(): List<AccountingClassMapping> = map.listAll()
}
