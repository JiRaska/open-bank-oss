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
import io.restassured.path.json.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0341 D3 against the real database: the accountingClass -> GL map seeded by V32 is exactly the
 * table below (the treasury rows are the other half of treasury's `PostingJournalsUnchangedTest`,
 * which proves new rules + this map reproduce the legacy chart), and a journal posted by class lands
 * on the very same accounts as one posted by `glAccountId`.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
class AccountingClassGlMapIT {

    /** (accountingClass, role, currency) -> GL code. Keep identical to treasury's `LedgerMapGolden` + billing rows. */
    private val golden: Map<Triple<String, String, String>, String> = (
        listOf(
            row("NOSTRO_ACCOUNT", "PRINCIPAL", "CZK", "1001"),
            row("NOSTRO_ACCOUNT", "PRINCIPAL", "EUR", "1002"),
            row("INTERBANK_PLACEMENT", "PRINCIPAL", "CZK", "1500"),
            row("INTERBANK_PLACEMENT", "PRINCIPAL", "EUR", "1501"),
            row("INTERBANK_PLACEMENT", "ACCRUED_INTEREST", "CZK", "1520"),
            row("INTERBANK_PLACEMENT", "ACCRUED_INTEREST", "EUR", "1521"),
            row("INTERBANK_PLACEMENT", "INTEREST_INCOME", "CZK", "4200"),
            row("INTERBANK_PLACEMENT", "INTEREST_INCOME", "EUR", "4201"),
            row("INTERBANK_BORROWING", "PRINCIPAL", "CZK", "2300"),
            row("INTERBANK_BORROWING", "PRINCIPAL", "EUR", "2301"),
            row("INTERBANK_BORROWING", "ACCRUED_INTEREST", "CZK", "2310"),
            row("INTERBANK_BORROWING", "ACCRUED_INTEREST", "EUR", "2311"),
            row("INTERBANK_BORROWING", "INTEREST_EXPENSE", "CZK", "5200"),
            row("INTERBANK_BORROWING", "INTEREST_EXPENSE", "EUR", "5201"),
            row("CNB_DEPOSIT_FACILITY", "PRINCIPAL", "CZK", "1510"),
            row("CNB_DEPOSIT_FACILITY", "ACCRUED_INTEREST", "CZK", "1520"),
            row("CNB_DEPOSIT_FACILITY", "INTEREST_INCOME", "CZK", "4200"),
            row("CNB_LOMBARD_BORROWING", "PRINCIPAL", "CZK", "2320"),
            row("CNB_LOMBARD_BORROWING", "ACCRUED_INTEREST", "CZK", "2310"),
            row("CNB_LOMBARD_BORROWING", "INTEREST_EXPENSE", "CZK", "5200"),
            row("FX_SPOT_POSITION", "PRINCIPAL", "CZK", "1990"),
            row("FX_SPOT_POSITION", "PRINCIPAL", "EUR", "1991"),
        ) + listOf("CUSTOMER_DEPOSIT_DEMAND", "CUSTOMER_DEPOSIT_SAVINGS", "CUSTOMER_DEPOSIT_TERM").flatMap { cls ->
            listOf("CZK" to "2100", "EUR" to "2101", "USD" to "2102", "GBP" to "2103").map { (ccy, code) ->
                row(cls, "PRINCIPAL", ccy, code)
            }
        } + row("FEE_INCOME_ACCOUNT", "FEE_INCOME", "CZK", "4003")
        ).toMap()

    private fun row(accountingClass: String, role: String, currency: String, code: String) =
        Triple(accountingClass, role, currency) to code

    /**
     * The fixed id of a chart account: `a0000000-0000-0000-0000-00000000<code>`, except 2100, which
     * V3 seeded as `...0002` and which billing's configuration has always named by that id.
     */
    private fun chartId(code: String) =
        if (code == "2100") "a0000000-0000-0000-0000-000000000002" else "a0000000-0000-0000-0000-00000000$code"

    @Test
    fun `the seeded map is exactly the golden table, every row on an enabled leaf account`() {
        val body: String = Given { this } When { get("/api/v1/accounting-classes") } Then {
            statusCode(200)
        } Extract { asString() }
        val rows = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
        val actual = rows.associate {
            Triple(it["accountingClass"].asText(), it["role"].asText(), it["currencyCode"].asText()) to
                it["glCode"].asText()
        }
        assertThat(actual).isEqualTo(golden)
        assertThat(rows.size()).describedAs("no duplicate triple hides behind the map").isEqualTo(golden.size)
        // The chart's ids are fixed (see chartId); a row that resolved to
        // some other account id with the right code would pass the glCode comparison alone. Every row.
        rows.forEach {
            assertThat(it["glAccountId"].asText())
                .describedAs("${it["accountingClass"].asText()}/${it["role"].asText()}/${it["currencyCode"].asText()}")
                .isEqualTo(chartId(it["glCode"].asText()))
        }
    }

    @Test
    fun `resolve answers the mapped account and 422 for an unmapped triple`() {
        Given {
            queryParam("accountingClass", "CNB_LOMBARD_BORROWING")
            queryParam("role", "PRINCIPAL")
            queryParam("currency", "CZK")
        } When { get("/api/v1/accounting-classes/resolve") } Then {
            statusCode(200)
            body("glCode", equalTo("2320"))
            body("glAccountId", equalTo("a0000000-0000-0000-0000-000000002320"))
        }
        // The deposit facility is CZK only (ADR-0315): an EUR leg has no account, so it is refused.
        Given {
            queryParam("accountingClass", "CNB_DEPOSIT_FACILITY")
            queryParam("role", "PRINCIPAL")
            queryParam("currency", "EUR")
        } When { get("/api/v1/accounting-classes/resolve") } Then {
            statusCode(422)
            body("error", containsString("no GL account is mapped"))
        }
        Given {
            queryParam("accountingClass", "INTERBANK_PLACEMENT")
            queryParam("role", "NOT_A_ROLE")
            queryParam("currency", "CZK")
        } When { get("/api/v1/accounting-classes/resolve") } Then { statusCode(422) }
        Given { queryParam("role", "PRINCIPAL") } When { get("/api/v1/accounting-classes/resolve") } Then {
            statusCode(400)
        }
    }

    @Test
    fun `fee billing resolves to the two accounts its configuration used to name by id`() {
        // Billing's previous defaults (BillingLedgerConfig): debit a0..0002 (2100), credit a0..4003 (4003).
        Given {
            queryParam("accountingClass", "CUSTOMER_DEPOSIT_DEMAND")
            queryParam("role", "PRINCIPAL")
            queryParam("currency", "CZK")
        } When { get("/api/v1/accounting-classes/resolve") } Then {
            statusCode(200)
            body("glAccountId", equalTo("a0000000-0000-0000-0000-000000000002"))
        }
        Given {
            queryParam("accountingClass", "FEE_INCOME_ACCOUNT")
            queryParam("role", "FEE_INCOME")
            queryParam("currency", "CZK")
        } When { get("/api/v1/accounting-classes/resolve") } Then {
            statusCode(200)
            body("glAccountId", equalTo("a0000000-0000-0000-0000-000000004003"))
        }
    }

    @Test
    fun `a non-CZK fee debits its own control account, and its income leg is an explicit 422 until a chart decision`() {
        // Billing's old fixed 2100/4003 are CZK accounts and the ledger refuses a line whose currency is not
        // the account's, so a non-CZK fee has never posted. The debit now resolves to the currency's control
        // account; the income leg has no account to land in and says so, instead of a currency mismatch.
        for ((ccy, id) in listOf("EUR" to "2101", "USD" to "2102", "GBP" to "2103")) {
            Given {
                queryParam("accountingClass", "CUSTOMER_DEPOSIT_DEMAND")
                queryParam("role", "PRINCIPAL")
                queryParam("currency", ccy)
            } When { get("/api/v1/accounting-classes/resolve") } Then {
                statusCode(200)
                body("glAccountId", equalTo("a0000000-0000-0000-0000-00000000$id"))
            }
            Given {
                queryParam("accountingClass", "FEE_INCOME_ACCOUNT")
                queryParam("role", "FEE_INCOME")
                queryParam("currency", ccy)
            } When { get("/api/v1/accounting-classes/resolve") } Then {
                statusCode(422)
                body("error", containsString("no GL account is mapped"))
            }
        }
    }

    @Test
    fun `a journal posted by class lands on the same accounts as one posted by glAccountId`() {
        val today = LocalDate.now().toString()
        val byId = post(
            "by-id-${UUID.randomUUID()}",
            line(""""glAccountId":"a0000000-0000-0000-0000-000000001500"""", "DEBIT"),
            line(""""glAccountId":"a0000000-0000-0000-0000-000000001001"""", "CREDIT"),
            today,
        )
        val byClass = post(
            "by-class-${UUID.randomUUID()}",
            line(""""accountingClass":"INTERBANK_PLACEMENT","accountingRole":"PRINCIPAL"""", "DEBIT"),
            line(""""accountingClass":"NOSTRO_ACCOUNT","accountingRole":"PRINCIPAL"""", "CREDIT"),
            today,
        )
        assertThat(byClass.getList<String>("lines.glAccountId")).isEqualTo(byId.getList<String>("lines.glAccountId"))
        assertThat(byClass.getList<String>("lines.side")).isEqualTo(byId.getList<String>("lines.side"))
        assertThat(byClass.getList<String>("lines.amount")).isEqualTo(byId.getList<String>("lines.amount"))
    }

    @Test
    fun `a line naming both or neither way to the account is a 400, an unmapped class a 422`() {
        val today = LocalDate.now().toString()
        postExpecting(
            400,
            line(
                """"glAccountId":"a0000000-0000-0000-0000-000000001500","accountingClass":"INTERBANK_PLACEMENT","accountingRole":"PRINCIPAL"""",
                "DEBIT",
            ),
            line(""""glAccountId":"a0000000-0000-0000-0000-000000001001"""", "CREDIT"),
            today,
        )
        postExpecting(
            400,
            line(""""accountingClass":"INTERBANK_PLACEMENT"""", "DEBIT"),
            line(""""glAccountId":"a0000000-0000-0000-0000-000000001001"""", "CREDIT"),
            today,
        )
        postExpecting(
            400,
            line(""""side_only":"x"""", "DEBIT"),
            line(""""glAccountId":"a0000000-0000-0000-0000-000000001001"""", "CREDIT"),
            today,
        )
        postExpecting(
            422,
            line(""""accountingClass":"NOT_A_MAPPED_CLASS","accountingRole":"PRINCIPAL"""", "DEBIT"),
            line(""""glAccountId":"a0000000-0000-0000-0000-000000001001"""", "CREDIT"),
            today,
        )
    }

    private fun line(account: String, side: String) =
        """{$account,"side":"$side","amount":"500.00","currencyCode":"CZK","baseAmount":"500.00","baseCurrencyCode":"CZK"}"""

    private fun payload(key: String, a: String, b: String, date: String) =
        """{"idempotencyKey":"$key","transactionId":"${UUID.randomUUID()}","entryDate":"$date","valueDate":"$date",""" +
            """"description":"accounting class IT","createdBy":"${UUID.randomUUID()}","lines":[$a,$b]}"""

    private fun post(key: String, a: String, b: String, date: String): JsonPath = Given {
        contentType("application/json")
        body(payload(key, a, b, date))
    } When { post("/api/v1/journals") } Then { statusCode(201) } Extract { jsonPath() }

    private fun postExpecting(status: Int, a: String, b: String, date: String) {
        Given {
            contentType("application/json")
            body(payload("neg-${UUID.randomUUID()}", a, b, date))
        } When { post("/api/v1/journals") } Then { statusCode(status) }
    }
}
