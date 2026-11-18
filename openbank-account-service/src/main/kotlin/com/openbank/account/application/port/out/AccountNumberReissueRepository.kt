// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** One account's id and stored number, as the re-issue scan reads it. */
data class StoredAccountNumber(val accountId: UUID, val accountNumber: String)

/** The audit row of one re-issue (V35 `account_number_reissue`). */
data class AccountNumberReissue(
    val accountId: UUID,
    val oldAccountNumber: String,
    val newAccountNumber: String,
    val reason: String,
    val reissuedAt: Instant,
)

/** Persistence port for [com.openbank.account.application.usecase.AccountNumberReissueService]. */
interface AccountNumberReissueRepository {

    /** Every account of any status, keyset-paginated by id. */
    suspend fun pageAccountNumbers(afterId: UUID?, limit: Int): List<StoredAccountNumber>

    /** True when any account holds [accountNumber] (the generator's collision check). */
    suspend fun accountNumberTaken(accountNumber: String): Boolean

    /**
     * In ONE transaction: change the account's number from [AccountNumberReissue.oldAccountNumber] to
     * [AccountNumberReissue.newAccountNumber], insert the audit row, and write the event [event] builds
     * (given the account's new version) to the outbox. Returns false, writing nothing, when the account
     * no longer holds the old number (another pod re-issued it first).
     */
    suspend fun reissue(reissue: AccountNumberReissue, event: (newVersion: Long) -> DomainEvent): Boolean
}
