// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.contract

import org.junit.jupiter.api.Assertions.assertEquals
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
        // 1.4.0: minor bump for the documented credit/debit idempotency-on-referenceId guarantee.
        assertEquals("1.4.0", version)
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
