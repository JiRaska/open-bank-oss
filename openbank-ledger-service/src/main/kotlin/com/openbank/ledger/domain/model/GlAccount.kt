// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.domain.model

import com.openbank.libs.domain.money.CurrencyCode
import java.time.Instant
import java.util.UUID

data class GlAccount(
    val id: UUID,
    val code: String,
    val name: String,
    val type: GlAccountType,
    val currency: CurrencyCode,
    val parentId: UUID?,
    val isLeaf: Boolean,
    val isEnabled: Boolean,
    val createdAt: Instant,
) {
    /**
     * Customer deposit-control accounts (liability to the customer, one per currency:
     * 2100=CZK, 2101=EUR, 2102=USD, 2103=GBP). These are the only accounts that carry a
     * per-customer sub-ledger dimension (ADR-0039 Phase B): a journal line against one of
     * them may name the customer account via subAccountId.
     */
    val isDepositControl: Boolean
        get() = code in DEPOSIT_CONTROL_CODES

    companion object {
        val DEPOSIT_CONTROL_CODES = setOf("2100", "2101", "2102", "2103")
    }
}

enum class GlAccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE,
}
