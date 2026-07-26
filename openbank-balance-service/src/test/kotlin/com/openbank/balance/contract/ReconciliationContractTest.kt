// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.contract

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the ADR-0039 Phase A API change without booting the app: the published OpenAPI contract must
 * document the reconciliation endpoints and report schema, and the contract version must reflect the
 * minor bump. Adding the reconciliation REST surface and forgetting openapi.yaml (or vice-versa)
 * fails here.
 */
class ReconciliationContractTest {

    private companion object {
        // Collapse a semver triple into one comparable integer, so the floor above is a single
        // comparison. detekt's MagicNumber fires on literals at the call site, not on a constant.
        const val MAJOR_WEIGHT = 1_000_000
        const val MINOR_WEIGHT = 1_000
    }

    private val openapi = File("src/main/resources/openapi.yaml").readText()

    /**
     * A FLOOR, not an equality. The property this guards is that the reconciliation work carried
     * its own contract bump — true of "at least 1.6.0", and true forever. Pinning the exact string
     * made every LATER legitimate bump red: publishing the served-but-undocumented overdraft-limit
     * endpoint (#2314) is additive, takes a MINOR under ADR-0048 D5, and reddened a case that has
     * nothing to do with reconciliation. A test that fails on the correct next change is a test
     * whose assertion gets edited rather than read.
     *
     * History of the floor: 1.6.0 published PATCH /api/v1/balances/approvals/{id}, the ADR-0155
     * four-eyes decision point the service had always served and the contract never named (#2358).
     * 1.5.0 was ADR-0178 Phase 3, adding the optional `futureValueDatedPipeline` property to
     * CurrencyReconciliation (#1746); 1.4.1 was editorial, the 403 Forbidden documentation added
     * across every operation (ADR-0034 Phase 5, #266).
     */
    @Test
    fun `contract version is at or above the reconciliation change`() {
        val version = Regex("""(?m)^\s+version:\s*"?([^"\s]+)"?\s*$""").find(openapi)?.groupValues?.get(1)
        assertNotNull(version, "openapi.yaml declares no info.version")
        val (major, minor, patch) = version!!.split(".").map { it.toInt() }
        val actual = major * MAJOR_WEIGHT + minor * MINOR_WEIGHT + patch
        assertTrue(
            actual >= 1 * MAJOR_WEIGHT + 6 * MINOR_WEIGHT + 0,
            "info.version $version must be >= 1.6.0, the version the ADR-0039/0155 work published",
        )
    }

    /**
     * ADR-0048: an API change ships with a contract test. Locks the Phase 3 field to the *response*
     * schema and pins its additive shape — it must stay optional, so an older consumer that never
     * reads it keeps parsing the report unchanged.
     */
    @Test
    fun `the per-currency tie-out documents the future-value-dated pipeline as an optional field`() {
        val currencySchema = openapi
            .substringAfter("    CurrencyReconciliation:")
            .substringBefore("\n    ApiError:")

        assertTrue(
            currencySchema.contains("futureValueDatedPipeline:"),
            "CurrencyReconciliation must expose futureValueDatedPipeline (ADR-0178 Phase 3)",
        )
        assertFalse(
            currencySchema.contains("required:"),
            "futureValueDatedPipeline must stay OPTIONAL — making it required is a breaking MAJOR change",
        )
    }

    @Test
    fun `the reconciliation endpoints are documented`() {
        assertTrue(openapi.contains("/api/v1/balances/reconciliation"))
        assertTrue(openapi.contains("/api/v1/balances/reconciliation/latest"))
    }

    @Test
    fun `the reconciliation report schema documents the per-currency tie-out`() {
        val schema = openapi.substringAfter("    ReconciliationReport:")
        assertTrue(schema.contains("hasDrift:"), "ReconciliationReport must expose hasDrift")
        assertTrue(schema.contains("driftedCurrencies:"), "ReconciliationReport must expose driftedCurrencies")
        assertTrue(schema.contains("currencies:"), "ReconciliationReport must expose currencies")

        val currencySchema = openapi.substringAfter("    CurrencyReconciliation:")
        assertTrue(currencySchema.contains("ledgerControlBalance:"), "must expose ledgerControlBalance")
        assertTrue(currencySchema.contains("subLedgerBookedSum:"), "must expose subLedgerBookedSum")
        assertTrue(currencySchema.contains("difference:"), "must expose difference")
    }
}
