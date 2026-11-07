// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.domain

import com.openbank.libs.iso20022.InstitutionTransferRail
import java.util.Locale

/**
 * The three bank-own (financial-institution) credit-transfer routes the simulator stands in for:
 * exactly the bank-own rails, so the type is the rail vocabulary `openbank-libs-iso20022` owns
 * (its `currency` and `clearingSystem` say what a `pacs.009` on that route must carry).
 */
typealias ClearingRoute = InstitutionTransferRail

/** True when this route clears [currency] through [clearingSystem] (`null` = none declared). */
fun ClearingRoute.accepts(currency: String, clearingSystem: String?): Boolean =
    (this.currency == null || this.currency == currency) && this.clearingSystem == clearingSystem

object ClearingRoutes {
    /** The route named by a URL path segment (case-insensitive), or `null` if there is none. */
    fun fromPath(segment: String): ClearingRoute? =
        ClearingRoute.entries.firstOrNull { it.name == segment.uppercase(Locale.ROOT) }
}
