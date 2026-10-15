// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class DomesticPaymentCurrencyContractTest {
    @Test
    fun `create contract documents the CZK scheme rule and its 400 code`() {
        val openApi = File("src/main/resources/openapi.yaml").readText()
        val create = openApi.substringAfter("  /api/v1/domestic-payments:").substringBefore("    get:")
        val invalidMoney = openApi.substringAfter("    InvalidMoney:").substringBefore("    Forbidden:")
        val currency = openApi.substringAfter("        currency:").substringBefore("        variableSymbol:")

        assertThat(create).contains(
            "'400':",
            "#/components/responses/InvalidMoney",
            "'403':",
            "#/components/responses/Forbidden",
        )
        assertThat(invalidMoney).contains("CURRENCY_NOT_ALLOWED", "not CZK")
        assertThat(currency).contains("CZK only")
    }
}
