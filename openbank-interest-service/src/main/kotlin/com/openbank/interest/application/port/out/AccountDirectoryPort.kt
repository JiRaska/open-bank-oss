// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.out

import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.util.UUID

/**
 * The fleet directory the daily accrual run reads to discover which accounts to accrue and at what
 * balance. Deliberately narrow: only the fields the accrual formula needs. It is backed by
 * account-service's staff/service reads (`GET /api/v1/accounts/active` for discovery — the same
 * fleet-wide cursor list billing-service's cycle scheduler uses, ADR-0143 — and
 * `GET /api/v1/accounts/{id}/balance` for the booked balance).
 *
 * **Fail-open:** an unreachable account-service yields an empty page / a null balance, so a
 * scheduled tick degrades to "accrue nobody this run" (loud in logs, retried next tick) rather than
 * crashing the scheduler. Interest that is one day late is a self-healing lag; a crashed scheduler
 * is a silent multi-day gap.
 */
interface AccountDirectoryPort {
    /** One page of fleet-wide ACTIVE accounts. [cursor] is null for the first page; follow
     *  [AccountPage.nextCursor] until it is null. */
    fun listActiveAccounts(cursor: String?, limit: Int): Uni<AccountPage>

    /** The account's booked (cleared) balance — interest accrues on booked, not available, so a
     *  pending debit does not retroactively shrink the day's accrual. Null when unavailable. */
    fun bookedBalance(accountId: UUID): Uni<BalanceSnapshot?>
}

/** An account as the accrual run sees it. [productId] is the catalog product UUID rendered as a
 *  string — it keys the rate config lookup ([InterestRateConfigRepository.findActiveForProduct]). */
data class AccountSnapshot(val id: UUID, val productId: String, val accountType: String, val currency: String)

data class AccountPage(val items: List<AccountSnapshot>, val nextCursor: String?)

data class BalanceSnapshot(val booked: BigDecimal, val currency: String)
