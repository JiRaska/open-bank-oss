// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.account.application.port.out

import com.openbank.account.domain.model.Account
import java.time.Instant
import java.util.UUID

interface GsftAccountRosterRepository {
    /** Deposit accounts that existed immediately before [dayEndExclusive], ordered by immutable id. */
    suspend fun findDepositAccounts(dayEndExclusive: Instant, limit: Int, afterId: UUID?): List<Account>
}
