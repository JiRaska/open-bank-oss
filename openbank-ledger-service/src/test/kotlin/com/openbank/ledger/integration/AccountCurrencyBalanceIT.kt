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
import io.restassured.path.json.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * #11107: a foreign-currency GL account's balance in its OWN currency, summed from native
 * `amount` against a real Postgres.
 *
 * The rows are written straight over JDBC onto two GL accounts this test creates, so the expected
 * sums are absolute and every line is one the endpoint must either count or provably ignore:
 *
 * | line                                            | counted? |
 * |-------------------------------------------------|----------|
 * | EUR debit 1000 on the account, before asOf      | yes      |
 * | EUR credit 300 on the account, ON asOf          | yes (inclusive) |
 * | CZK base-only revaluation-shaped debit 500      | no — base-only, not EUR |
 * | EUR debit 50 on the account, AFTER asOf         | no       |
 * | PENDING EUR debit 70 on the account             | no       |
 * | CZK debit 999 + EUR debit 11 on ANOTHER account | no       |
 *
 * Dropping the currency filter makes the debit 1500 and the test red; so does summing
 * `base_amount` (26000) instead of `amount`.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class AccountCurrencyBalanceIT {

    private val code = "T" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val otherCode = "T" + UUID.randomUUID().toString().replace("-", "").take(12)

    @BeforeEach
    fun seed() {
        connection().use { c ->
            val account = insertAccount(c, code, "EUR")
            val other = insertAccount(c, otherCode, "CZK")
            posted(c, BEFORE).line(c, account, "D", "1000", "EUR", "25000", null)
            posted(c, AS_OF).line(c, account, "C", "300", "EUR", "7500", null)
            // FX revaluation shape (FxRevaluationPosting): a CZK line, base CZK, fx_rate carried.
            posted(c, AS_OF).line(c, account, "D", "500", "CZK", "500", "25.1")
            posted(c, AFTER).line(c, account, "D", "50", "EUR", "1250", null)
            entry(c, BEFORE, "PENDING").line(c, account, "D", "70", "EUR", "1750", null)
            posted(c, BEFORE).line(c, other, "D", "999", "CZK", "999", null)
            posted(c, BEFORE).line(c, other, "D", "11", "EUR", "275", null)
        }
    }

    /**
     * The seeded journals are deliberately unbalanced and mixed-currency, so they must not outlive
     * this class: the trial-balance tie-out and the journal listing in sibling ITs read the same
     * database and would fail on them.
     */
    @AfterEach
    fun cleanUp() {
        connection().use { c ->
            c.prepareStatement(
                "delete from journal_entries where id in (select jl.journal_id from journal_lines jl " +
                    "join gl_accounts ga on ga.id = jl.gl_account_id where ga.code in (?, ?))",
            ).use {
                it.setString(1, code)
                it.setString(2, otherCode)
                it.executeUpdate()
            }
            c.prepareStatement(
                "delete from journal_lines where gl_account_id in (select id from gl_accounts where code in (?, ?))",
            ).use {
                it.setString(1, code)
                it.setString(2, otherCode)
                it.executeUpdate()
            }
            c.prepareStatement("delete from gl_accounts where code in (?, ?)")
                .use {
                    it.setString(1, code)
                    it.setString(2, otherCode)
                    it.executeUpdate()
                }
        }
    }

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_VIEWER"])
    fun `sums native EUR up to asOf inclusive, ignoring base-only, later, pending and other-account lines`() {
        val body = balance(code, AS_OF.toString(), "EUR")
        assertThat(body.getString("code")).isEqualTo(code)
        assertThat(body.getString("currency")).isEqualTo("EUR")
        assertThat(body.getString("asOf")).isEqualTo(AS_OF.toString())
        assertThat(BigDecimal(body.getString("debit"))).isEqualByComparingTo("1000")
        assertThat(BigDecimal(body.getString("credit"))).isEqualByComparingTo("300")
        assertThat(BigDecimal(body.getString("net"))).isEqualByComparingTo("700")
    }

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_VIEWER"])
    fun `the base-only revaluation line is visible only when its own currency is asked for`() {
        val body = balance(code, AS_OF.toString(), "CZK")
        assertThat(BigDecimal(body.getString("debit"))).isEqualByComparingTo("500")
        assertThat(BigDecimal(body.getString("credit"))).isEqualByComparingTo("0")
    }

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_VIEWER"])
    fun `an account with no lines in the currency reports zero, not an error`() {
        val body = balance(otherCode, AS_OF.toString(), "USD")
        assertThat(BigDecimal(body.getString("net"))).isEqualByComparingTo("0")
    }

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_VIEWER"])
    fun `missing or malformed parameters are 400 and an unknown account is 404`() {
        status(code, asOf = null, currency = "EUR", expected = 400)
        status(code, asOf = AS_OF.toString(), currency = null, expected = 400)
        status(code, asOf = AS_OF.toString(), currency = "eur", expected = 400)
        status(code, asOf = "2026-13-40", currency = "EUR", expected = 400)
        status(
            "NOPE-${UUID.randomUUID().toString().take(8)}",
            asOf = AS_OF.toString(),
            currency = "EUR",
            expected = 404,
        )
    }

    private fun balance(account: String, asOf: String, currency: String): JsonPath = (
        Given {
            queryParam("asOf", asOf)
            queryParam("currency", currency)
        } When {
            get("/api/v1/journals/accounts/$account/balance")
        } Then {
            statusCode(200)
        }
        ).extract().jsonPath()

    private fun status(account: String, asOf: String?, currency: String?, expected: Int) {
        Given {
            if (asOf != null) queryParam("asOf", asOf) else this
            if (currency != null) queryParam("currency", currency) else this
        } When {
            get("/api/v1/journals/accounts/$account/balance")
        } Then {
            statusCode(expected)
        }
    }

    private class Entry(val id: UUID, val date: LocalDate)

    private fun posted(c: Connection, date: LocalDate) = entry(c, date, "POSTED")

    private fun entry(c: Connection, date: LocalDate, status: String): Entry {
        val id = UUID.randomUUID()
        c.prepareStatement(
            "insert into journal_entries (id, transaction_id, entry_date, value_date, status, created_by) " +
                "values (?, ?, ?, ?, ?, ?)",
        ).use {
            it.setObject(1, id)
            it.setObject(2, UUID.randomUUID())
            it.setDate(3, Date.valueOf(date))
            it.setDate(4, Date.valueOf(date))
            it.setString(5, status)
            it.setObject(6, UUID.fromString(USER))
            it.executeUpdate()
        }
        return Entry(id, date)
    }

    @Suppress("LongParameterList")
    private fun Entry.line(
        c: Connection,
        account: UUID,
        side: String,
        amount: String,
        currency: String,
        baseAmount: String,
        fxRate: String?,
    ) {
        c.prepareStatement(
            "insert into journal_lines (journal_id, gl_account_id, side, amount, currency_code, fx_rate, " +
                "base_amount, base_currency, sequence) values (?, ?, ?, ?, ?, ?, ?, 'CZK', 1)",
        ).use {
            it.setObject(1, id)
            it.setObject(2, account)
            it.setString(3, side)
            it.setBigDecimal(4, BigDecimal(amount))
            it.setString(5, currency)
            it.setBigDecimal(6, fxRate?.let(::BigDecimal))
            it.setBigDecimal(7, BigDecimal(baseAmount))
            it.executeUpdate()
        }
    }

    private fun insertAccount(c: Connection, accountCode: String, currency: String): UUID {
        val id = UUID.randomUUID()
        c.prepareStatement(
            "insert into gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) " +
                "values (?, ?, 'IT nostro', 'ASSET', ?, true, true)",
        ).use {
            it.setObject(1, id)
            it.setString(2, accountCode)
            it.setString(3, currency)
            it.executeUpdate()
        }
        return id
    }

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    companion object {
        private const val USER = "00000000-0000-0000-0000-000000000099"
        private val AS_OF: LocalDate = LocalDate.of(2026, 3, 10)
        private val BEFORE: LocalDate = AS_OF.minusDays(2)
        private val AFTER: LocalDate = AS_OF.plusDays(1)
    }
}
