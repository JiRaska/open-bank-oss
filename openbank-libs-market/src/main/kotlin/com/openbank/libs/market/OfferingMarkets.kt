// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.market

import java.util.Locale

@JvmInline
value class MarketCountry(val code: String) {
    init {
        require(code in countryCodes) { "Market country must be an ISO 3166-1 alpha-2 code" }
    }

    companion object {
        private val countryCodes = Locale.getISOCountries().toSet()
    }
}

/** Product distribution restrictions. A listed country still requires a separate passport grant. */
class OfferingMarkets(countries: Set<String>) {
    private val allowed = countries.mapTo(mutableSetOf(), ::MarketCountry)

    fun includes(country: MarketCountry): Boolean = country in allowed
}
