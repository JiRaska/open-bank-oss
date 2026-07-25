// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private val openapi = File("src/main/resources/openapi.yaml").readText()

    @Test
    fun `contract version is bumped for the reconciliation change`() {
        val version = Regex("""(?m)^\s+version:\s*"?([^"\s]+)"?\s*$""").find(openapi)?.groupValues?.get(1)
        // 1.6.0: MINOR — publishes PATCH /api/v1/balances/approvals/{id}, the ADR-0155 four-eyes
        // decision point, which the service has always served and the contract never named
        // (issue #2358). Additive: a path added, nothing removed or made required.
        // 1.5.0 was ADR-0178 Phase 3 adding the optional `futureValueDatedPipeline` property to
        // CurrencyReconciliation (issue #1746); 1.4.1 was an editorial bump for the 403 Forbidden
        // documentation added across every operation (ADR-0034 Phase 5, issue #266).
        assertEquals("1.6.0", version)
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
