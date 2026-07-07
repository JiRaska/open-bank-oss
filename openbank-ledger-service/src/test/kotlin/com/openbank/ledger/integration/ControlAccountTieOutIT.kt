// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

// Regression test for a control-account tie-out that 500'd on every call: the native SQL
// filtered on jl.account_id, a column that doesn't exist on journal_lines (the real column is
// gl_account_id). No test previously exercised this endpoint.
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class ControlAccountTieOutIT {

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `GET tie-out reconciles GL against sub-ledger for a control account`() {
        // Seeded by V5__fx_position_accounts.sql. GBP is used deliberately: no other IT class in
        // this module posts against these accounts, so the cumulative-to-date tie-out aggregate
        // (which sums ALL posted activity for the account, not just this test's) stays isolated —
        // unlike the CZK deposit-control account (2100) which LedgerApiIT et al. also post to.
        val glFxPositionGbpId = "a0000000-0000-0000-0000-000000001993"
        val controlAccountId = "a0000000-0000-0000-0000-000000002103"
        val customerSubAccountId = UUID.randomUUID()
        val today = LocalDate.now().toString()

        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "${UUID.randomUUID()}",
              "entryDate": "$today",
              "valueDate": "$today",
              "description": "Control-account tie-out IT",
              "createdBy": "00000000-0000-0000-0000-000000000099",
              "lines": [
                {
                  "glAccountId": "$glFxPositionGbpId",
                  "side": "DEBIT",
                  "amount": "500.00",
                  "currencyCode": "GBP",
                  "baseAmount": "500.00",
                  "baseCurrencyCode": "GBP"
                },
                {
                  "glAccountId": "$controlAccountId",
                  "side": "CREDIT",
                  "amount": "500.00",
                  "currencyCode": "GBP",
                  "baseAmount": "500.00",
                  "baseCurrencyCode": "GBP",
                  "subAccountId": "$customerSubAccountId"
                }
              ]
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(201)
            body("status", equalTo("POSTED"))
        }

        Given {
            queryParam("asOf", today)
        } When {
            get("/api/v1/control-accounts/$controlAccountId/tie-out")
        } Then {
            statusCode(200)
            body("find { it.currency == 'GBP' }.glNet", equalTo(500.00f))
            body("find { it.currency == 'GBP' }.subLedgerNet", equalTo(500.00f))
            body("find { it.currency == 'GBP' }.delta", equalTo(0.00f))
            body("find { it.currency == 'GBP' }.tiedOut", equalTo(true))
            body("find { it.currency == 'GBP' }.lines[0].subAccountId", equalTo(customerSubAccountId.toString()))
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_AUDITOR"])
    fun `GET tie-out for a control account with no posted activity returns 200`() {
        Given {
            queryParam("asOf", "2020-01-01")
        } When {
            get("/api/v1/control-accounts/${UUID.randomUUID()}/tie-out")
        } Then {
            statusCode(200)
        }
    }
}
