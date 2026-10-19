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
 * ADR-0344 D7: V33 seeds the accounts the treasury posting-rule matrix posts to. Posting a balanced
 * journal to EVERY one of them through the real API, in its own currency, is the proof that each
 * fixed id exists with the currency it is used in — and a line in the other currency is refused,
 * so a matrix row naming the wrong currency's account fails loudly instead of posting. Treasury
 * names its legs by accounting class + role (ADR-0341 D3), so every (class, role, currency) the
 * matrix uses is also posted BY CLASS and must land on the account V33 maps it to.
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
    fun `every V33 account accepts a balanced posting in its own currency`() {
        val refused = mutableListOf<String>()
        CZK_CODES.forEach { code ->
            val pairs = listOf(Triple(code, "1001", "CZK"), Triple((code.toInt() + 1).toString(), "1002", "EUR"))
            pairs.forEach { (c, nostroCode, ccy) ->
                val status = post(
                    journal(
                        "treasury:it-v33-$c:settlement",
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
    fun `a V33 account refuses a line in the other currency`() {
        val status = post(
            journal(
                "treasury:it-v33-wrong-ccy:settlement",
                line("1600", "DEBIT", "5.00", "EUR"),
                line("1002", "CREDIT", "5.00", "EUR"),
            ),
        )
        assertThat(status).isEqualTo(422)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `every matrix class, role and currency resolves and posts by class onto its mapped account`() {
        val failures = mutableListOf<String>()
        MATRIX_CLASSES.forEach { (triple, code) ->
            val (cls, role, ccy) = triple
            val resolved: String = Given {
                queryParam("accountingClass", cls)
                queryParam("role", role)
                queryParam("currency", ccy)
            } When { get("/api/v1/accounting-classes/resolve") } Then { } Extract
                { path<String?>("glAccountId") ?: "none" }
            if (resolved != id(code)) failures += "$cls/$role/$ccy resolved $resolved, not ${id(code)}"
            val nostroCode = if (ccy == "CZK") "1001" else "1002"
            val key = "treasury:it-v33-class-$cls-$role-$ccy:settlement"
            val leg = """{"accountingClass":"$cls","accountingRole":"$role","side":"DEBIT","amount":"7.00",
                "currencyCode":"$ccy","baseAmount":"7.00","baseCurrencyCode":"$ccy"}"""
            val status = post(journal(key, leg, line(nostroCode, "CREDIT", "7.00", ccy)))
            if (status != 201) failures += "$cls/$role/$ccy -> $status"
        }
        assertThat(failures).isEmpty()
        assertThat(MATRIX_CLASSES).hasSize(78)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a matrix class with a role it does not map is refused, never defaulted`() {
        val leg = """{"accountingClass":"TREASURY_LOAN","accountingRole":"FAIR_VALUE","side":"DEBIT","amount":"1.00",
            "currencyCode":"CZK","baseAmount":"1.00","baseCurrencyCode":"CZK"}"""
        assertThat(post(journal("treasury:it-v33-unmapped:settlement", leg, line("1001", "CREDIT", "1.00", "CZK"))))
            .isEqualTo(422)
    }

    companion object {
        /** Every (class, role, currency) V33 maps; keep identical to treasury's `LedgerMapGolden`. */
        val MATRIX_CLASSES: Map<Triple<String, String, String>, String> = listOf(
            Triple("CASH_IN_TRANSIT", "PRINCIPAL", "CZK") to "1690",
            Triple("CASH_IN_TRANSIT", "PRINCIPAL", "EUR") to "1691",
            Triple("TREASURY_LOAN", "PRINCIPAL", "CZK") to "1660",
            Triple("TREASURY_LOAN", "PRINCIPAL", "EUR") to "1661",
            Triple("TREASURY_LOAN", "ACCRUED_INTEREST", "CZK") to "1650",
            Triple("TREASURY_LOAN", "ACCRUED_INTEREST", "EUR") to "1651",
            Triple("TREASURY_LOAN", "INTEREST_INCOME", "CZK") to "4220",
            Triple("TREASURY_LOAN", "INTEREST_INCOME", "EUR") to "4221",
            Triple("DEBT_SECURITY_AMORTISED_COST", "PRINCIPAL", "CZK") to "1600",
            Triple("DEBT_SECURITY_AMORTISED_COST", "PRINCIPAL", "EUR") to "1601",
            Triple("DEBT_SECURITY_AMORTISED_COST", "PREMIUM_DISCOUNT", "CZK") to "1630",
            Triple("DEBT_SECURITY_AMORTISED_COST", "PREMIUM_DISCOUNT", "EUR") to "1631",
            Triple("DEBT_SECURITY_AMORTISED_COST", "ACCRUED_INTEREST", "CZK") to "1640",
            Triple("DEBT_SECURITY_AMORTISED_COST", "ACCRUED_INTEREST", "EUR") to "1641",
            Triple("DEBT_SECURITY_AMORTISED_COST", "INTEREST_INCOME", "CZK") to "4210",
            Triple("DEBT_SECURITY_AMORTISED_COST", "INTEREST_INCOME", "EUR") to "4211",
            Triple("DEBT_SECURITY_FVOCI", "PRINCIPAL", "CZK") to "1610",
            Triple("DEBT_SECURITY_FVOCI", "PRINCIPAL", "EUR") to "1611",
            Triple("DEBT_SECURITY_FVOCI", "PREMIUM_DISCOUNT", "CZK") to "1630",
            Triple("DEBT_SECURITY_FVOCI", "PREMIUM_DISCOUNT", "EUR") to "1631",
            Triple("DEBT_SECURITY_FVOCI", "ACCRUED_INTEREST", "CZK") to "1640",
            Triple("DEBT_SECURITY_FVOCI", "ACCRUED_INTEREST", "EUR") to "1641",
            Triple("DEBT_SECURITY_FVOCI", "INTEREST_INCOME", "CZK") to "4210",
            Triple("DEBT_SECURITY_FVOCI", "INTEREST_INCOME", "EUR") to "4211",
            Triple("DEBT_SECURITY_FVTPL", "PRINCIPAL", "CZK") to "1620",
            Triple("DEBT_SECURITY_FVTPL", "PRINCIPAL", "EUR") to "1621",
            Triple("DEBT_SECURITY_FVTPL", "PREMIUM_DISCOUNT", "CZK") to "1630",
            Triple("DEBT_SECURITY_FVTPL", "PREMIUM_DISCOUNT", "EUR") to "1631",
            Triple("DEBT_SECURITY_FVTPL", "ACCRUED_INTEREST", "CZK") to "1640",
            Triple("DEBT_SECURITY_FVTPL", "ACCRUED_INTEREST", "EUR") to "1641",
            Triple("DEBT_SECURITY_FVTPL", "INTEREST_INCOME", "CZK") to "4210",
            Triple("DEBT_SECURITY_FVTPL", "INTEREST_INCOME", "EUR") to "4211",
            Triple("DEBT_SECURITY_FVOCI", "REVALUATION_RESERVE", "CZK") to "3100",
            Triple("DEBT_SECURITY_FVOCI", "REVALUATION_RESERVE", "EUR") to "3101",
            Triple("REPO_BORROWING", "PRINCIPAL", "CZK") to "2430",
            Triple("REPO_BORROWING", "PRINCIPAL", "EUR") to "2431",
            Triple("REPO_BORROWING", "ACCRUED_INTEREST", "CZK") to "2420",
            Triple("REPO_BORROWING", "ACCRUED_INTEREST", "EUR") to "2421",
            Triple("REPO_BORROWING", "INTEREST_EXPENSE", "CZK") to "5240",
            Triple("REPO_BORROWING", "INTEREST_EXPENSE", "EUR") to "5241",
            Triple("REVERSE_REPO_LENDING", "PRINCIPAL", "CZK") to "1680",
            Triple("REVERSE_REPO_LENDING", "PRINCIPAL", "EUR") to "1681",
            Triple("REVERSE_REPO_LENDING", "ACCRUED_INTEREST", "CZK") to "1685",
            Triple("REVERSE_REPO_LENDING", "ACCRUED_INTEREST", "EUR") to "1686",
            Triple("REVERSE_REPO_LENDING", "INTEREST_INCOME", "CZK") to "4240",
            Triple("REVERSE_REPO_LENDING", "INTEREST_INCOME", "EUR") to "4241",
            Triple("DERIVATIVE_ASSET", "FAIR_VALUE", "CZK") to "1700",
            Triple("DERIVATIVE_ASSET", "FAIR_VALUE", "EUR") to "1701",
            Triple("DERIVATIVE_LIABILITY", "FAIR_VALUE", "CZK") to "2400",
            Triple("DERIVATIVE_LIABILITY", "FAIR_VALUE", "EUR") to "2401",
            Triple("DERIVATIVE_RECEIVE_LEG", "ACCRUED_INTEREST", "CZK") to "1670",
            Triple("DERIVATIVE_RECEIVE_LEG", "ACCRUED_INTEREST", "EUR") to "1671",
            Triple("DERIVATIVE_RECEIVE_LEG", "INTEREST_INCOME", "CZK") to "4230",
            Triple("DERIVATIVE_RECEIVE_LEG", "INTEREST_INCOME", "EUR") to "4231",
            Triple("DERIVATIVE_PAY_LEG", "ACCRUED_INTEREST", "CZK") to "2410",
            Triple("DERIVATIVE_PAY_LEG", "ACCRUED_INTEREST", "EUR") to "2411",
            Triple("DERIVATIVE_PAY_LEG", "INTEREST_EXPENSE", "CZK") to "5230",
            Triple("DERIVATIVE_PAY_LEG", "INTEREST_EXPENSE", "EUR") to "5231",
            Triple("TREASURY_TRADING_RESULT", "REALISED_GAIN", "CZK") to "4300",
            Triple("TREASURY_TRADING_RESULT", "REALISED_GAIN", "EUR") to "4301",
            Triple("TREASURY_TRADING_RESULT", "REALISED_LOSS", "CZK") to "5300",
            Triple("TREASURY_TRADING_RESULT", "REALISED_LOSS", "EUR") to "5301",
            Triple("TREASURY_TRADING_RESULT", "UNREALISED_GAIN", "CZK") to "4310",
            Triple("TREASURY_TRADING_RESULT", "UNREALISED_GAIN", "EUR") to "4311",
            Triple("TREASURY_TRADING_RESULT", "UNREALISED_LOSS", "CZK") to "5310",
            Triple("TREASURY_TRADING_RESULT", "UNREALISED_LOSS", "EUR") to "5311",
            Triple("FORWARD_COMMITMENT", "MEMO", "CZK") to "9000",
            Triple("FORWARD_COMMITMENT", "MEMO", "EUR") to "9001",
            Triple("FORWARD_COMMITMENT", "MEMO_CONTRA", "CZK") to "9100",
            Triple("FORWARD_COMMITMENT", "MEMO_CONTRA", "EUR") to "9101",
            Triple("COLLATERAL_RECEIVED", "MEMO", "CZK") to "9010",
            Triple("COLLATERAL_RECEIVED", "MEMO", "EUR") to "9011",
            Triple("COLLATERAL_RECEIVED", "MEMO_CONTRA", "CZK") to "9110",
            Triple("COLLATERAL_RECEIVED", "MEMO_CONTRA", "EUR") to "9111",
            Triple("COLLATERAL_PLEDGED", "MEMO", "CZK") to "9020",
            Triple("COLLATERAL_PLEDGED", "MEMO", "EUR") to "9021",
            Triple("COLLATERAL_PLEDGED", "MEMO_CONTRA", "CZK") to "9120",
            Triple("COLLATERAL_PLEDGED", "MEMO_CONTRA", "EUR") to "9121",
        ).toMap()

        /** The CZK code of every V33 pair (EUR = code + 1). Must list the migration exactly. */
        val CZK_CODES = listOf(
            "1600", "1610", "1620", "1630", "1640", "1650", "1660", "1670", "1680", "1685", "1690", "1700",
            "2400", "2410", "2420", "2430", "3100", "4210", "4220", "4230", "4240", "4300", "4310",
            "5230", "5240", "5300", "5310", "9000", "9100", "9010", "9110", "9020", "9120",
        )
    }
}
