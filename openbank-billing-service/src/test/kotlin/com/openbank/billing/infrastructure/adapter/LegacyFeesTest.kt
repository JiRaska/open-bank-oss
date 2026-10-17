// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The bridge recomputes the catalog's fee price-component code from a v1 fee id. The vectors below
 * are the SAME literals the catalog pins in `BankV1FeeCodeVectorTest`; if either side changes the
 * algorithm, one of the two tests goes red and the bridge cannot silently stop matching (which would
 * make every fee fail closed to a non-waivable one under a new idempotency key).
 */
class LegacyFeesTest {

    @Test
    fun `price codes match the catalog's literal vectors`() {
        assertThat(LegacyFees.priceCode("f1")).isEqualTo("FEE_F1_3F524CDC07A11D7C6220")
        assertThat(LegacyFees.priceCode("excess-withdrawal"))
            .isEqualTo("FEE_EXCESS_WITHDRAWAL_7524DF43737E1210743F")
        assertThat(LegacyFees.priceCode("3f20a53e-5ea4-307a-8a0f-9605728381ba"))
            .isEqualTo("FEE_3F20A53E_5EA4_307A_8A0F_9605728381BA_9B3EDD9EEE85705FCBC9")
    }

    @Test
    fun `an embedded document keys its fees by price code and tolerates absence`() {
        val doc = """{"fees":[{"id":"f1","name":"Maintenance","type":"MONTHLY",""" +
            """"waivable":true,"waiveCondition":"x"}]}"""
        assertThat(LegacyFees.byPriceCode(doc).getValue("FEE_F1_3F524CDC07A11D7C6220").name).isEqualTo("Maintenance")
        assertThat(LegacyFees.byPriceCode(null)).isEmpty()
        assertThat(LegacyFees.byPriceCode("")).isEmpty()
    }

    @Test
    fun `every v1 product type implies a deposit class, and only an unknown one implies none`() {
        assertThat(LegacyProductTypes.accountingClassOf("CURRENT")).isEqualTo("CUSTOMER_DEPOSIT_DEMAND")
        assertThat(LegacyProductTypes.accountingClassOf("SAVINGS")).isEqualTo("CUSTOMER_DEPOSIT_SAVINGS")
        assertThat(LegacyProductTypes.accountingClassOf("TERM_DEPOSIT")).isEqualTo("CUSTOMER_DEPOSIT_TERM")
        // Billed against the customer's current account before ADR-0341, so still billed (not silently skipped).
        listOf("LOAN", "MORTGAGE", "CREDIT_CARD", "OVERDRAFT", "INVESTMENT").forEach {
            assertThat(LegacyProductTypes.accountingClassOf(it)).describedAs(it).isEqualTo("CUSTOMER_DEPOSIT_DEMAND")
        }
        listOf("FUTURE_TYPE", "TERM_LIFE", "", null).forEach {
            assertThat(LegacyProductTypes.accountingClassOf(it)).isNull()
        }
    }

    @Test
    fun `the mapped set is exactly the v1 ProductType enum and the legacy-product schema enum`() {
        assertThat(LegacyProductTypes.KNOWN).containsExactlyInAnyOrder(
            "SAVINGS",
            "CURRENT",
            "LOAN",
            "MORTGAGE",
            "CREDIT_CARD",
            "TERM_DEPOSIT",
            "OVERDRAFT",
            "INVESTMENT",
        )
        val schema = java.nio.file.Path.of(
            "../openbank-product-catalog/src/main/resources/catalog-packs/banking/legacy-product-v1.schema.json",
        )
        assertThat(java.nio.file.Files.exists(schema)).describedAs("catalog schema readable from the build").isTrue()
        val enumValues = com.fasterxml.jackson.databind.ObjectMapper().readTree(schema.toFile())
            .path("properties").path("productType").path("enum").map { it.asText() }
        assertThat(LegacyProductTypes.KNOWN).containsExactlyInAnyOrderElementsOf(enumValues)
    }
}
