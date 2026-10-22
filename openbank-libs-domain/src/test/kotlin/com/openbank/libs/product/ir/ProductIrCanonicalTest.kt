// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProductIrCanonicalTest {

    private val base = IrFixtures.savingsJson

    @Test
    fun `0_025 and 0_0250 authored two ways hash identically`() {
        val a = ProductIrParser.parse(base.replace("\"rate\": \"0.0250\"", "\"rate\": \"0.025\""))
        val b = ProductIrParser.parse(base.replace("\"rate\": \"0.0250\"", "\"rate\": 0.0250"))
        val c = ProductIrParser.parse(base)
        assertThat(a.revisionHash).isEqualTo(b.revisionHash).isEqualTo(c.revisionHash)
    }

    @Test
    fun `a reordered object hashes identically`() {
        val reordered = base.replace(
            "\"irVersion\": 1,\n  \"productCode\": \"SAV-FIXED\",\n  \"currency\": \"EUR\",",
            "\"currency\": \"EUR\",\n  \"productCode\": \"SAV-FIXED\",\n  \"irVersion\": 1,",
        ).replace(
            "\"rateType\": \"FIXED\",\n    \"rate\": \"0.0250\",",
            "\"rate\": \"0.0250\",\n    \"rateType\": \"FIXED\",",
        )
        assertThat(reordered).isNotEqualTo(base)
        assertThat(ProductIrParser.parse(reordered).revisionHash).isEqualTo(ProductIrParser.parse(base).revisionHash)
    }

    @Test
    fun `a changed ruleId does not`() {
        val renamed = ProductIrParser.parse(
            base.replace(
                "\"ruleId\": \"fee.maintenance\"",
                "\"ruleId\": \"fee.maint\"",
            ).replace("\"feeRuleId\": \"fee.maintenance\"", "\"feeRuleId\": \"fee.maint\""),
        )
        assertThat(renamed.revisionHash).isNotEqualTo(ProductIrParser.parse(base).revisionHash)
    }

    @Test
    fun `canonical form has sorted keys, no whitespace and fixed-scale decimal strings`() {
        val json = ProductIrCanonical.canonicalJson(IrFixtures.savings())
        assertThat(json).doesNotContain(" ", "\n")
        assertThat(json).contains("\"rate\":\"0.0250000000\"", "\"amount\":\"5.0000\"", "\"threshold\":\"50000.0000\"")
        assertThat(json).startsWith("{\"currency\":\"EUR\",\"fees\":[")
        assertThat(json).doesNotContain("revisionHash")
    }

    @Test
    fun `the hash is the SHA-256 of the canonical bytes`() {
        val rev = IrFixtures.savings()
        assertThat(
            rev.revisionHash,
        ).isEqualTo(com.openbank.libs.audit.AuditChain.sha256Hex(ProductIrCanonical.canonicalJson(rev)))
    }

    @Test
    fun `every action and predicate kind has a canonical form`() {
        val rev = IrFixtures.termDeposit()
        val json = ProductIrCanonical.canonicalJson(rev)
        assertThat(
            json,
        ).contains("\"type\":\"MATURE\"", "\"type\":\"EARLY_WITHDRAWAL_PENALTY\"", "\"cadence\":\"AT_MATURITY\"")
        val reprice = ProductIrCanonical.predicate(
            Predicate.Or(
                listOf(
                    Predicate.PostingComparison(
                        PostingAttribute.POSTING_AMOUNT,
                        com.openbank.libs.product.WaiveOperator.GT,
                        java.math.BigDecimal.ONE,
                    ),
                    Predicate.CountEvents(EventKind.FEE, Window(3), com.openbank.libs.product.WaiveOperator.LT, 2),
                    Predicate.SumEvents(
                        EventKind.DEPOSIT,
                        Window(3),
                        com.openbank.libs.product.WaiveOperator.GTE,
                        java.math.BigDecimal.TEN,
                    ),
                ),
            ),
        )
        assertThat(reprice["type"]).isEqualTo("or")
        assertThat((reprice["operands"] as List<*>)).hasSize(3)
    }
}
