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

    private companion object {
        // Collapse a semver triple into one comparable integer, so the floor below is a single
        // comparison rather than three nested ones. detekt's MagicNumber fires on the literals
        // at the call site, not on a named constant.
        const val MAJOR_WEIGHT = 1_000_000
        const val MINOR_WEIGHT = 1_000
    }

    private val openapi = File("src/main/resources/openapi.yaml").readText()

    /**
     * A FLOOR, not an equality. The point of this case is that the ADR-0033/0038 remittance work
     * carried its own contract bump — that is a property of "info.version is at least 1.3.0", and
     * it stays true forever. Pinning the exact string instead made every LATER legitimate bump
     * red: publishing the two served-but-undocumented interest routes (#2314) is additive, takes a
     * MINOR under ADR-0048 D5, and broke this test while changing nothing this test is about. A
     * test that fails on the correct next change is a test that gets its assertion edited rather
     * than read, which is how the value it guards stops meaning anything.
     */
    @Test
    fun `contract version is at or above the remittance change`() {
        val version = Regex("""(?m)^\s+version:\s*"?([^"\s]+)"?\s*$""").find(openapi)?.groupValues?.get(1)
        assertThat(version).`as`("openapi.yaml info.version").isNotNull()
        val (major, minor, patch) = version!!.split(".").map { it.toInt() }
        assertThat(major * MAJOR_WEIGHT + minor * MINOR_WEIGHT + patch)
            .`as`("info.version %s must be >= 1.3.0, the version the ADR-0038 remittance work published", version)
            .isGreaterThanOrEqualTo(1 * MAJOR_WEIGHT + 3 * MINOR_WEIGHT + 0)
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
