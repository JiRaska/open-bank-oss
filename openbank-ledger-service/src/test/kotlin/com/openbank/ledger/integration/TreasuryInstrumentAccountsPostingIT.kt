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
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D7: V31 seeds the accounts the treasury posting-rule matrix posts to. Posting a balanced
 * journal to EVERY one of them through the real API, in its own currency, is the proof that each
 * fixed id exists with the currency it is used in — and a line in the other currency is refused,
 * so a matrix row naming the wrong currency's account fails loudly instead of posting.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class TreasuryInstrumentAccountsPostingIT {
    private val today = LocalDate.now().toString()

    private fun id(code: String) = "a0000000-0000-0000-0000-00000000$code"

    private fun line(code: String, side: String, amount: String, ccy: String) = """
        {"glAccountId":"${id(code)}","side":"$side","amount":"$amount","currencyCode":"$ccy",
         "baseAmount":"$amount","baseCurrencyCode":"$ccy"}
    """.trimIndent()

    private fun journal(key: String, vararg lines: String) = """
        {"idempotencyKey":"$key","transactionId":"${UUID.randomUUID()}","entryDate":"$today",
         "valueDate":"$today","description":"treasury matrix IT","createdBy":"00000000-0000-0000-0000-000000000099",
         "lines":[${lines.joinToString(",")}]}
    """.trimIndent()

    private fun post(body: String): Int = Given {
        contentType("application/json")
        body(body)
    } When {
        post("/api/v1/journals")
    } Then {
    } Extract {
        statusCode()
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `every V31 account accepts a balanced posting in its own currency`() {
        val refused = mutableListOf<String>()
        CZK_CODES.forEach { code ->
            val pairs = listOf(Triple(code, "1001", "CZK"), Triple((code.toInt() + 1).toString(), "1002", "EUR"))
            pairs.forEach { (c, nostroCode, ccy) ->
                val status = post(
                    journal(
                        "treasury:it-v31-$c:settlement",
                        line(c, "DEBIT", "12.34", ccy),
                        line(nostroCode, "CREDIT", "12.34", ccy),
                    ),
                )
                if (status != 201) refused += "$c/$ccy -> $status"
            }
        }
        assertThat(refused).isEmpty()
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a V31 account refuses a line in the other currency`() {
        val status = post(
            journal(
                "treasury:it-v31-wrong-ccy:settlement",
                line("1600", "DEBIT", "5.00", "EUR"),
                line("1002", "CREDIT", "5.00", "EUR"),
            ),
        )
        assertThat(status).isEqualTo(422)
    }

    companion object {
        /** The CZK code of every V31 pair (EUR = code + 1). Must list the migration exactly. */
        val CZK_CODES = listOf(
            "1600", "1610", "1620", "1630", "1640", "1650", "1660", "1670", "1680", "1685", "1690", "1700",
            "2400", "2410", "2420", "2430", "3100", "4210", "4220", "4230", "4240", "4300", "4310",
            "5230", "5240", "5300", "5310", "9000", "9100", "9010", "9110", "9020", "9120",
        )
    }
}
