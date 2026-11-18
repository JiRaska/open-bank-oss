// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

/**
 * Czech domestic payment symbols — variabilní (VS), konstantní (KS) and specifický (SS) symbol.
 * Each is up to ten decimal digits (ČBA standard for domestic payments; vyhláška ČNB 169/2011 Sb.
 * carries them as numeric fields of that width). Leading zeros are insignificant but allowed.
 */
object CzechPaymentSymbol {
    const val MAX_LENGTH = 10

    /** True iff [value] is 1..10 ASCII digits. Null/blank is "no symbol" and is the caller's call. */
    fun isValid(value: String): Boolean = value.length in 1..MAX_LENGTH && value.all { it in '0'..'9' }

    /** [value] unchanged, or [IllegalArgumentException] naming [field]. Null passes (symbols are optional). */
    fun requireValid(field: String, value: String?): String? {
        if (value != null) require(isValid(value)) { "$field must be 1-$MAX_LENGTH digits" }
        return value
    }
}
