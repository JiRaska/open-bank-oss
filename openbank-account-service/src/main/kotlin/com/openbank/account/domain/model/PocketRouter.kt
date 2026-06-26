// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.domain.model

import com.openbank.libs.domain.money.CurrencyCode

/**
 * Decides which currency pocket settles a payment on a single-IBAN multi-currency account
 * (ADR-0024). Resolution: an operable pocket in the payment currency wins; otherwise the
 * account's [MissingPocketPolicy] decides between rejecting, auto-creating a pocket, or
 * converting to the primary currency. Pure — no I/O — so it is fully unit-testable.
 */
object PocketRouter {

    fun resolve(
        pockets: List<CurrencyPocket>,
        paymentCurrency: CurrencyCode,
        primaryCurrency: CurrencyCode,
        policy: MissingPocketPolicy,
    ): PocketResolution {
        val match = pockets.firstOrNull { it.currency == paymentCurrency }
        if (match != null) {
            return if (match.isOperable()) {
                PocketResolution.UseExisting(match)
            } else {
                PocketResolution.Rejected(
                    "Pocket ${paymentCurrency.code} is not operable (status=${match.status})",
                )
            }
        }

        return when (policy) {
            MissingPocketPolicy.REJECT ->
                PocketResolution.Rejected("No ${paymentCurrency.code} pocket and policy is REJECT")

            MissingPocketPolicy.AUTO_CREATE ->
                PocketResolution.CreateNew(paymentCurrency)

            MissingPocketPolicy.CONVERT_TO_PRIMARY -> {
                val primary = pockets.firstOrNull { it.currency == primaryCurrency }
                    ?: return PocketResolution.Rejected(
                        "Primary pocket ${primaryCurrency.code} missing; cannot convert",
                    )
                if (primary.isOperable()) {
                    PocketResolution.ConvertToPrimary(paymentCurrency, primary)
                } else {
                    PocketResolution.Rejected(
                        "Primary pocket ${primaryCurrency.code} not operable (status=${primary.status})",
                    )
                }
            }
        }
    }
}

sealed interface PocketResolution {
    /** Credit/debit the existing pocket as-is, no FX. */
    data class UseExisting(val pocket: CurrencyPocket) : PocketResolution

    /** Open a new pocket in [currency], then credit it as-is, no FX. */
    data class CreateNew(val currency: CurrencyCode) : PocketResolution

    /** Convert [from] into the primary currency and settle on [primary] (explicit FX leg required). */
    data class ConvertToPrimary(val from: CurrencyCode, val primary: CurrencyPocket) : PocketResolution

    /** Routing failed; the payment must be returned/declined with [reason]. */
    data class Rejected(val reason: String) : PocketResolution
}
