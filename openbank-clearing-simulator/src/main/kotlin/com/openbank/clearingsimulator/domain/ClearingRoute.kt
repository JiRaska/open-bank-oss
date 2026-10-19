// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.domain

import java.util.Locale

/**
 * The three bank-own (financial-institution) credit-transfer routes the simulator stands in for.
 * [currency] is the only currency the route clears (`null` = any, the correspondent route), and
 * [clearingSystem] the `GrpHdr/SttlmInf/ClrSys` value a `pacs.009` on that route must carry.
 */
enum class ClearingRoute(val currency: String?, val clearingSystem: String?) {
    /** The domestic CZK RTGS. */
    CERTIS("CZK", "CERTIS"),

    /** The euro RTGS. */
    TARGET2("EUR", "TGT"),

    /** Correspondent banking over the FI messaging network — any currency, no clearing system. */
    SWIFT(null, null),
    ;

    /** True when this route clears [currency] through [clearingSystem] (`null` = none declared). */
    fun accepts(currency: String, clearingSystem: String?): Boolean =
        (this.currency == null || this.currency == currency) && this.clearingSystem == clearingSystem

    companion object {
        /** The route named by a URL path segment (case-insensitive), or `null` if there is none. */
        fun fromPath(segment: String): ClearingRoute? =
            entries.firstOrNull { it.name == segment.uppercase(Locale.ROOT) }
    }
}
