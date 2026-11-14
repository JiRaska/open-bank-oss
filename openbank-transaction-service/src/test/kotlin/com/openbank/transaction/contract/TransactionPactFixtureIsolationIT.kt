// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.contract

import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Deployed and main Pact versions reuse the same key in one broker verification JVM. */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000000123", roles = ["ROLE_OPERATOR"])
class TransactionPactFixtureIsolationIT {
    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    @AfterEach
    fun resetRegressionData() {
        // Arrange and remove only this regression's fixed keys; never a table-wide truncate.
        dataSource.connection.use { c ->
            check(c.catalog == "openbank_transactions_it")
            c.createStatement().use { statement ->
                val keys = "('pact-domestic-txn-001', 'pact-sepa-txn-001')"
                statement.executeUpdate(
                    "delete from transaction_outbox where aggregate_id in (select id from transactions where idempotency_key in $keys)",
                )
                if (hasRegistry(
                        c,
                    )
                ) {
                    statement.executeUpdate(
                        "delete from transaction_idempotency_keys where idempotency_key in $keys",
                    )
                }
                statement.executeUpdate("delete from transactions where idempotency_key in $keys")
            }
        }
    }

    @Test
    fun `domestic deployed then main request each sees its own instruction type`() = assertOrder(DOMESTIC, false)

    @Test
    fun `domestic main then deployed request each sees its own instruction type`() = assertOrder(DOMESTIC, true)

    @Test
    fun `SEPA deployed then main request each sees its own instruction type`() = assertOrder(SEPA, false)

    @Test
    fun `SEPA main then deployed request each sees its own instruction type`() = assertOrder(SEPA, true)

    private fun assertOrder(corpus: Corpus, modernFirst: Boolean) {
        val fixture = TransactionPactProviderVerificationTest().also { it.dataSource = dataSource }
        fixture.stateValidSourceAccountExists()
        val first = post(corpus, modernFirst)
        assertThat(first.getString("instructionType")).isEqualTo(if (modernFirst) "ONE_OFF" else null)
        val beforeReset = counts(corpus.key)
        assertThat(beforeReset.first).isEqualTo(1)
        assertThat(beforeReset.second).isGreaterThan(0)

        // Independent transaction/outbox are preserved even in the same source account.
        val other = corpus.copy(key = "fixture-unrelated-${UUID.randomUUID()}")
        val unrelated = post(other, true)
        val unrelatedCounts = counts(other.key)
        fixture.stateValidSourceAccountExists()
        val afterReset = counts(corpus.key)
        val second = post(corpus, !modernFirst)
        assertThat(second.getString("instructionType")).isEqualTo(if (modernFirst) null else "ONE_OFF")
        assertThat(afterReset).isEqualTo(0 to 0)
        assertThat(counts(other.key)).isEqualTo(unrelatedCounts)
        assertThat(second.getString("id")).isNotEqualTo(first.getString("id"))
        assertThat(unrelated.getString("instructionType")).isEqualTo("ONE_OFF")
        registryCount(corpus.key)?.let { assertThat(it).isEqualTo(1) }
    }

    @Test
    fun `fixture keeps another account even when it uses a reserved Pact key`() {
        val otherAccount = DOMESTIC.copy(account = "cccccccc-cccc-cccc-cccc-cccccccccccc")
        val original = post(otherAccount, true)
        val before = counts(otherAccount.key)
        TransactionPactProviderVerificationTest().also { it.dataSource = dataSource }.stateValidSourceAccountExists()
        assertThat(counts(otherAccount.key)).isEqualTo(before)
        assertThat(post(otherAccount, true).getString("id")).isEqualTo(original.getString("id"))
    }

    @Test
    fun `reset refuses every database except its isolated Testcontainers catalog`() {
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) {
                _,
                method,
                _,
            ->
            when (method.name) {
                "getCatalog" -> "not_the_fixture"
                "close" -> null
                else -> error("No SQL or transaction operation allowed before catalog guard")
            }
        } as Connection
        val guarded = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            check(method.name == "getConnection")
            connection
        } as DataSource
        assertThatThrownBy { PactTransactionSeed.resetInitiationState(guarded) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("isolated transaction Testcontainers database")
    }

    @Test
    fun `retained registry claim without an owned Pact row is never released`() {
        if (createRetainedClaim(DOMESTIC.key)) {
            TransactionPactProviderVerificationTest().also {
                it.dataSource = dataSource
            }.stateValidSourceAccountExists()
            assertThat(registryCount(DOMESTIC.key)).isEqualTo(1)
        }
        assertThat(counts(DOMESTIC.key)).isEqualTo(0 to 0)
    }

    private fun createRetainedClaim(key: String): Boolean = dataSource.connection.use { c ->
        if (!hasRegistry(c)) return@use false
        c.prepareStatement(
            "insert into transaction_idempotency_keys (idempotency_key, transaction_id, booking_date) values (?, ?, ?)",
        ).use { q ->
            q.setString(1, key)
            q.setObject(2, UUID.randomUUID())
            q.setObject(3, java.time.LocalDate.parse("2026-01-20"))
            q.executeUpdate()
        }
        true
    }

    private fun registryCount(key: String): Int? = dataSource.connection.use { c ->
        if (!hasRegistry(c)) return@use null
        c.prepareStatement("select count(*) from transaction_idempotency_keys where idempotency_key = ?").use { q ->
            q.setString(1, key)
            q.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun post(corpus: Corpus, modern: Boolean): io.restassured.path.json.JsonPath {
        val body = mutableMapOf<String, Any>(
            "idempotencyKey" to corpus.key,
            "type" to corpus.type,
            "sourceAccountId" to corpus.account,
            "amount" to corpus.amount,
            "currencyCode" to corpus.currency,
            "description" to "synthetic Pact fixture isolation",
            "valueDate" to "2026-01-20",
            "rail" to corpus.rail,
        )
        if (modern) body["instructionType"] = "ONE_OFF"
        return given().contentType("application/json").body(body)
            .post("/api/v1/transactions").then().statusCode(201).extract().jsonPath()
    }

    private fun counts(key: String): Pair<Int, Int> = dataSource.connection.use { c ->
        c.prepareStatement(
            "select count(*), (select count(*) from transaction_outbox where aggregate_id in " +
                "(select id from transactions where idempotency_key = ?)) from transactions where idempotency_key = ?",
        ).use { q ->
            q.setString(1, key)
            q.setString(2, key)
            q.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1) to rows.getInt(2)
            }
        }
    }

    private fun hasRegistry(c: Connection): Boolean = c.createStatement().use { q ->
        q.executeQuery("select to_regclass('transaction_idempotency_keys') is not null").use { rows ->
            rows.next()
            rows.getBoolean(1)
        }
    }

    private data class Corpus(
        val key: String,
        val account: String,
        val rail: String,
        val type: String,
        val currency: String,
        val amount: Double,
    )

    companion object {
        private val DOMESTIC =
            Corpus("pact-domestic-txn-001", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "DOMESTIC", "DEBIT", "CZK", 1000.0)
        private val SEPA =
            Corpus("pact-sepa-txn-001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "SEPA_CT", "TRANSFER", "EUR", 250.0)
    }
}
