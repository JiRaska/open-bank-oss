// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.libs.market

import java.util.UUID

/** The consumed passport receipt owns this context; clients cannot choose it. */
data class BookingContext(val bankId: UUID, val establishmentId: UUID, val hostCountry: String, val mode: String) {
    init {
        require(mode in setOf("FOS", "BRANCH"))
        MarketCountry(hostCountry)
        require(hostCountry != "CZ")
    }
}
