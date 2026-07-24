// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the ADR-0033 API change without booting the app: the published OpenAPI contract must
 * document the withholding-tax split (gross/tax/net) on the capitalization result, and the contract
 * version must reflect the minor bump. Adding a withholding field to [com.openbank.interest.domain
 * .model.InterestCapitalization] and forgetting openapi.yaml (or vice-versa) fails here.
 */
class CapitalizationContractTest {

    private val openapi = File("src/main/resources/openapi.yaml").readText()

    @Test
    fun `contract version is bumped for the remittance change`() {
        val version = Regex("""(?m)^\s+version:\s*"?([^"\s]+)"?\s*$""").find(openapi)?.groupValues?.get(1)
        assertThat(version).isEqualTo("1.3.0")
    }

    @Test
    fun `capitalization schema documents the gross-tax-net split`() {
        val capSchema = openapi.substringAfter("    Capitalization:")
        assertThat(capSchema)
            .`as`("openapi.yaml Capitalization schema must document the ADR-0033 withholding split")
            .contains("grossAmount:")
            .contains("taxAmount:")
            .contains("netAmount:")
    }

    @Test
    fun `the remittance endpoint and batch schema are documented`() {
        assertThat(openapi).contains("/api/v1/interest/withholding/remittances")
        assertThat(openapi).contains("/api/v1/interest/withholding/remittances/{year}/{month}")
        val schema = openapi.substringAfter("    WithholdingRemittance:")
        assertThat(schema)
            .`as`("openapi.yaml WithholdingRemittance schema must document the ADR-0038 batch shape")
            .contains("totalTaxAmount:")
            .contains("dueDate:")
            .contains("itemCount:")
    }
}
