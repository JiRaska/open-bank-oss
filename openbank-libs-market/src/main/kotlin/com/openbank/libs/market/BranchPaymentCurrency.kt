// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.libs.market

/**
 * A closed accounting capability, not an HTTP API's general currency vocabulary.
 * A valid ISO code or a product denomination alone does not imply an available posting route.
 * Ledger maps these exhaustively; adding a capability requires its GL mapping and accounts.
 */
sealed class BranchPaymentCurrency private constructor(val code: String) {
    data object CZK : BranchPaymentCurrency("CZK")
    data object EUR : BranchPaymentCurrency("EUR")
    data object USD : BranchPaymentCurrency("USD")
    data object GBP : BranchPaymentCurrency("GBP")

    companion object {
        private val supported: List<BranchPaymentCurrency> by lazy { listOf(CZK, EUR, USD, GBP) }

        fun of(code: String): BranchPaymentCurrency = supported.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("No branch payment accounts for currency")

        fun supportedCodes(): Set<String> = supported.map { it.code }.toSet()
    }
}
