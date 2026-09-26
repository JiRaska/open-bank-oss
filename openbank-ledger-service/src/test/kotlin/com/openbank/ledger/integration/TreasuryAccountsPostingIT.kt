// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0315: treasury-service addresses the ledger's accounts by the FIXED ids V29 seeds
 * (`a0000000-0000-0000-0000-00000000<code>`), CZK nostro 1001 included — which V1 created with a
 * random id and V29 re-keys while unreferenced. Posting through the real API is the only proof
 * that every id the treasury posting table uses exists, with the currency it is used in.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class TreasuryAccountsPostingIT {

    private val today = LocalDate.now().toString()

    private fun id(code: String) = "a0000000-0000-0000-0000-00000000$code"

    private fun line(code: String, side: String, amount: String, ccy: String) = """
        {"glAccountId":"${id(code)}","side":"$side","amount":"$amount","currencyCode":"$ccy",
         "baseAmount":"$amount","baseCurrencyCode":"$ccy"}
    """.trimIndent()

    private fun journal(key: String, vararg lines: String) = """
        {"idempotencyKey":"$key","transactionId":"${UUID.randomUUID()}","entryDate":"$today",
         "valueDate":"$today","description":"treasury IT","createdBy":"00000000-0000-0000-0000-000000000099",
         "lines":[${lines.joinToString(",")}]}
    """.trimIndent()

    private fun post(body: String, status: Int): String? = Given {
        contentType("application/json")
        body(body)
    } When {
        post("/api/v1/journals")
    } Then {
        statusCode(status)
    } Extract {
        if (status == 201) path<String>("id") else null
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `every CZK treasury account posts, nostro 1001 by its fixed id`() {
        post(
            journal(
                "treasury:it-czk:settled",
                line("1500", "DEBIT", "100.00", "CZK"),
                line("1001", "CREDIT", "100.00", "CZK"),
            ),
            201,
        )
        post(
            journal(
                "treasury:it-cnb:settled",
                line("1510", "DEBIT", "50.00", "CZK"),
                line("1001", "CREDIT", "50.00", "CZK"),
            ),
            201,
        )
        post(
            journal(
                "treasury:it-czk:matured",
                line("1001", "DEBIT", "101.00", "CZK"),
                line("1500", "CREDIT", "100.00", "CZK"),
                line("4200", "CREDIT", "1.00", "CZK"),
            ),
            201,
        )
        post(
            journal(
                "treasury:it-czk-borrow:matured",
                line("2300", "DEBIT", "10.00", "CZK"),
                line("5200", "DEBIT", "0.10", "CZK"),
                line("1001", "CREDIT", "10.10", "CZK"),
            ),
            201,
        )
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `every EUR treasury account posts, and a replayed key returns the original journal`() {
        val body = journal(
            "treasury:it-eur:matured",
            line("1002", "DEBIT", "10.10", "EUR"),
            line("1501", "CREDIT", "10.00", "EUR"),
            line("4201", "CREDIT", "0.10", "EUR"),
        )
        val first = post(body, 201)
        val replay = post(body, 201)
        assertThat(replay).isEqualTo(first)
        post(
            journal(
                "treasury:it-eur-borrow:matured",
                line("2301", "DEBIT", "10.00", "EUR"),
                line("5201", "DEBIT", "0.10", "EUR"),
                line("1002", "CREDIT", "10.10", "EUR"),
            ),
            201,
        )
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `the ČNB facility account is CZK only`() {
        Given {
            contentType("application/json")
            body(
                journal(
                    "treasury:it-cnb-eur:settled",
                    line("1510", "DEBIT", "5.00", "EUR"),
                    line("1002", "CREDIT", "5.00", "EUR"),
                ),
            )
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(equalTo(422))
        }
    }
}
