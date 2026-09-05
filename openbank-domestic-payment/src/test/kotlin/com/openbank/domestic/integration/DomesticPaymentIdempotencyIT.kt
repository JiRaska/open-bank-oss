// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.it.PostgresRedisTestResource
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

/** Real Postgres/HTTP proof that the DB row—not Redis—is create-payment replay authority. */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DomesticPaymentIdempotencyIT {
    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @BeforeEach
    fun clearOwnRows() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM domestic_payment_outbox WHERE aggregate_id IN " +
                    "(SELECT payment_id FROM domestic_payments WHERE idempotency_key LIKE 'idempotency-it-%')",
            ).use { it.executeUpdate() }
            connection.prepareStatement(
                "DELETE FROM domestic_payments WHERE idempotency_key LIKE 'idempotency-it-%'",
            ).use { it.executeUpdate() }
        }
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `exact replay returns one durable payment while a changed command fails with problem 409`() {
        val key = "idempotency-it-exact-${UUID.randomUUID()}"
        val debtorAccountId = UUID.randomUUID()
        val request = requestBody(debtorAccountId, BigDecimal("1500.00"))

        val created = post(key, request)
        assertThat(created.statusCode).isEqualTo(201)
        assertThat(created.header("X-Idempotency-Replayed")).isNull()
        val paymentId = created.jsonPath().getString("id")

        val replay = post(key, request)
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replay.jsonPath().getString("id")).isEqualTo(paymentId)

        val changed = post(key, requestBody(debtorAccountId, BigDecimal("1500.01")))
        assertThat(changed.statusCode).isEqualTo(409)
        assertThat(changed.contentType).startsWith("application/problem+json")
        assertThat(changed.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(countPayments(key)).isEqualTo(1)
        assertThat(countOutbox(UUID.fromString(paymentId))).isEqualTo(1)
        assertThat(requestFingerprint(key)).matches("[0-9a-f]{64}")
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `a forged legacy Redis response cannot short-circuit Postgres creation`() {
        val key = "idempotency-it-redis-${UUID.randomUUID()}"
        redis.value(String::class.java)
            .set(
                "idempotency:$key",
                "201|2026-09-01T00:00:00Z|{\"id\":\"00000000-0000-0000-0000-000000000666\"}",
            )
            .await().indefinitely()

        val response = post(key, requestBody(UUID.randomUUID(), BigDecimal("42.00")))

        assertThat(response.statusCode).isEqualTo(201)
        assertThat(response.header("X-Idempotency-Replayed")).isNull()
        assertThat(response.jsonPath().getString("id")).isNotEqualTo("00000000-0000-0000-0000-000000000666")
        assertThat(countPayments(key)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `legacy row with no request fingerprint fails closed`() {
        val key = "idempotency-it-legacy-${UUID.randomUUID()}"
        val debtorAccountId = UUID.randomUUID()
        insertLegacyPayment(key, debtorAccountId)

        val response = post(key, requestBody(debtorAccountId, BigDecimal("10.00")))

        assertThat(response.statusCode).isEqualTo(409)
        assertThat(response.contentType).startsWith("application/problem+json")
        assertThat(response.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(countPayments(key)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `concurrent duplicates converge on one payment and one outbox event`() {
        val key = "idempotency-it-race-${UUID.randomUUID()}"
        val request = requestBody(UUID.randomUUID(), BigDecimal("99.95"))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)
        try {
            val calls = (1..CONCURRENT_REQUESTS).map {
                CompletableFuture.supplyAsync(
                    {
                        start.await()
                        post(key, request)
                    },
                    executor,
                )
            }
            start.countDown()
            val responses = calls.map { it.join() }

            assertThat(responses).allSatisfy { assertThat(it.statusCode).isEqualTo(201) }
            val ids = responses.map { it.jsonPath().getString("id") }.toSet()
            assertThat(ids).hasSize(1)
            assertThat(responses.count { it.header("X-Idempotency-Replayed") == "true" })
                .isEqualTo(CONCURRENT_REQUESTS - 1)
            assertThat(countPayments(key)).isEqualTo(1)
            assertThat(countOutbox(UUID.fromString(ids.single()))).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `fingerprint check is validated while legacy null remains permitted`() {
        val key = "idempotency-it-constraint-${UUID.randomUUID()}"
        insertLegacyPayment(key, UUID.randomUUID())
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT convalidated FROM pg_constraint WHERE conname = " +
                    "'chk_domestic_payments_request_fingerprint'",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getBoolean(1)).isTrue()
                }
            }

            assertThatThrownBy {
                connection.prepareStatement(
                    "UPDATE domestic_payments SET request_fingerprint = 'not-a-sha256' " +
                        "WHERE idempotency_key = ?",
                ).use { statement ->
                    statement.setString(1, key)
                    statement.executeUpdate()
                }
            }.isInstanceOf(SQLException::class.java)
        }
    }

    private fun post(key: String, body: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .body(body)
        .post("/api/v1/domestic-payments")

    private fun requestBody(debtorAccountId: UUID, amount: BigDecimal): String =
        """
        {
          "debtorAccountId": "$debtorAccountId",
          "debtorAccountNumber": " 1234567890 ",
          "debtorBankCode": " 0800 ",
          "debtorName": " Alice Example ",
          "creditorAccountNumber": " 9876543210 ",
          "creditorBankCode": " 0100 ",
          "creditorName": " Brno Utility ",
          "amount": $amount,
          "currency": " czk ",
          "variableSymbol": " 2026001 ",
          "specificSymbol": null,
          "constantSymbol": " 0308 ",
          "messageForPayee": " Utility bill ",
          "priority": "STANDARD",
          "transferScope": null,
          "technicalAccountCode": null,
          "statementLabel": " Monthly settlement ",
          "endToEndId": "E2E-IDEMPOTENCY-IT"
        }
        """.trimIndent()

    private fun insertLegacyPayment(key: String, debtorAccountId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO domestic_payments (
                    id, payment_id, idempotency_key, status, debtor_account_id, debtor_account_number,
                    debtor_bank_code, debtor_name, creditor_account_number, creditor_bank_code,
                    creditor_name, amount, currency, priority, transfer_scope, end_to_end_id,
                    created_at, updated_at, request_fingerprint
                ) VALUES (nextval('domestic_payments_seq'), ?, ?, 'RECEIVED', ?, '1234567890', '0800', 'Alice Example',
                    '9876543210', '0100', 'Brno Utility', 10.00, 'CZK', 'STANDARD', 'EXTERNAL',
                    'E2E-IDEMPOTENCY-IT', NOW(), NOW(), NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, key)
                statement.setObject(3, debtorAccountId)
                statement.executeUpdate()
            }
        }
    }

    private fun countPayments(key: String): Int = queryCount(
        "SELECT COUNT(*) FROM domestic_payments WHERE idempotency_key = ?",
        key,
    )

    private fun countOutbox(paymentId: UUID): Int = queryCount(
        "SELECT COUNT(*) FROM domestic_payment_outbox WHERE aggregate_id = ?",
        paymentId,
    )

    private fun queryCount(sql: String, value: Any): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, value)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private fun requestFingerprint(key: String): String? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT request_fingerprint FROM domestic_payments WHERE idempotency_key = ?",
        ).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"
        const val CONCURRENT_REQUESTS = 8
    }
}
