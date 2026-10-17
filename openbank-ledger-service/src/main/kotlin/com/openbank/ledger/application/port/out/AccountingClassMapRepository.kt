// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.AccountingClassMapping
import com.openbank.ledger.domain.model.AccountingRole

/** Read-only port over the ledger-owned accountingClass -> GL map; the map changes by migration only. */
interface AccountingClassMapRepository {
    suspend fun find(accountingClass: String, role: AccountingRole, currencyCode: String): AccountingClassMapping?

    suspend fun listAll(): List<AccountingClassMapping>
}
