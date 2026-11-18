// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.libs.market

/**
 * Currencies backed by the reference branch-payment posting plan, shared by admission and Ledger.
 * A valid ISO code or a product denomination alone does not imply an available accounting route.
 * Ledger maps these exhaustively; adding a currency also requires its GL mapping and accounts.
 */
enum class BranchPaymentCurrency {
    CZK,
    EUR,
    USD,
    GBP,
    ;

    companion object {
        fun of(code: String): BranchPaymentCurrency = entries.firstOrNull { it.name == code }
            ?: throw IllegalArgumentException("No branch payment accounts for currency")
    }
}
