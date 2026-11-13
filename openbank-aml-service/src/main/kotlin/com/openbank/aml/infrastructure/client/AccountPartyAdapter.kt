// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.client

import com.openbank.aml.application.port.out.AccountPartyPort
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** account-service owns account -> party; the lookup runs off the event loop. */
@ApplicationScoped
class AccountPartyAdapter(private val accounts: AccountServiceClient) : AccountPartyPort {
    override suspend fun findPartyByAccountId(accountId: UUID): UUID? =
        withContext(Dispatchers.IO) { accounts.findPartyByAccountId(accountId) }
}
