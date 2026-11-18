// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.libs.market

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Currency

class BranchPaymentCurrencyTest {
    @Test
    fun `an ISO denomination is not sufficient for a branch accounting route`() {
        for (code in listOf("PLN", "HUF", "CHF")) {
            val iso = Currency.getInstance(code).currencyCode
            assertThrows(IllegalArgumentException::class.java) { BranchPaymentCurrency.of(iso) }
        }
    }

    @Test
    fun `the supported canonical euro route is admitted without accepting raw lowercase input`() {
        assertEquals(BranchPaymentCurrency.EUR, BranchPaymentCurrency.of("EUR"))
        assertThrows(IllegalArgumentException::class.java) { BranchPaymentCurrency.of("eur") }
    }
}
