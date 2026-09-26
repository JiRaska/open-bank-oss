// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.openbank.sepa.application.port.`in`.SepaPaymentUseCase
import com.openbank.sepa.application.usecase.SepaPaymentService
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.arc.ClientProxy
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * #10916 — an `Idempotency-Key` is bound to the request it was first used with. Driven through the
 * real REST endpoint with the real Redis store and Postgres: a same-body retry replays, a different
 * body under the same key is refused with 409 IDEMPOTENCY_KEY_REUSED and creates nothing, a retry
 * whose JSON differs only in key order, whitespace or amount scale still replays (the fingerprint
 * is taken over the canonicalised DTO), a failed create releases the key, and the durable
 * `sepa_payments.request_hash` check still refuses a reused key once the Redis record is gone.
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
class SepaPaymentIdempotencyFingerprintIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Inject
    lateinit var useCase: SepaPaymentUseCase

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `same key and same body replays the first response without a second payment`() {
        val key = UUID.randomUUID().toString()
        val debtor = UUID.randomUUID()
        val first = post(key, body(debtor, "1234.56"))
        assertThat(first.statusCode).isEqualTo(201)

        val replay = post(key, body(debtor, "1234.56"))
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replay.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"))
        assertThat(paymentsFor(debtor)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `same key and a different body is refused with 409 and creates nothing`() {
        val key = UUID.randomUUID().toString()
        val debtor = UUID.randomUUID()
        assertThat(post(key, body(debtor, "1234.56")).statusCode).isEqualTo(201)

        val reused = post(key, body(debtor, "9999.99"))
        assertThat(reused.statusCode).isEqualTo(409)
        assertThat(reused.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(reused.header("X-Idempotency-Replayed")).isNull()
        assertThat(paymentsFor(debtor)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `same body with different key order and whitespace still replays`() {
        val key = UUID.randomUUID().toString()
        val debtor = UUID.randomUUID()
        val first = post(key, body(debtor, "1234.56"))
        assertThat(first.statusCode).isEqualTo(201)

        val reordered = """
            {"currency":"EUR",   "amount":1234.56, "creditorName":"Berlin Utility",
              "creditorIban":"DE89370400440532013000","debtorName":"Alice Example",
              "debtorIban":"CZ6508000000192000145399","creditorBic":"COBADEFFXXX",
              "remittanceInfo":"Utility bill","debtorAccountId":"$debtor","type":"SCT","endToEndId":null}
        """.trimIndent()
        val replay = post(key, reordered)
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(paymentsFor(debtor)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `amount scale does not change the fingerprint`() {
        val key = UUID.randomUUID().toString()
        val debtor = UUID.randomUUID()
        assertThat(post(key, body(debtor, "1234.5")).statusCode).isEqualTo(201)

        val replay = post(key, body(debtor, "1234.50"))
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(paymentsFor(debtor)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `a failed create releases the key so the same request can be retried`() {
        val key = UUID.randomUUID().toString()
        val debtor = UUID.randomUUID()
        val real = ClientProxy.unwrap(useCase) as SepaPaymentService
        val failingOnce = mockk<SepaPaymentService>()
        var calls = 0
        coEvery { failingOnce.createPayment(any()) } coAnswers {
            check(++calls > 1) { "transient failure on the first create" }
            real.createPayment(firstArg())
        }
        QuarkusMock.installMockForType(failingOnce, SepaPaymentUseCase::class.java)

        // IllegalStateException is mapped to 422 by libs-runtime; any non-2xx proves the create failed.
        assertThat(post(key, body(debtor, "1234.56")).statusCode).isEqualTo(422)
        assertThat(paymentsFor(debtor)).isEqualTo(0)

        // Without release() the in-flight marker would hold the key: 409 IN_PROGRESS for 5 minutes.
        val retry = post(key, body(debtor, "1234.56"))
        assertThat(retry.statusCode).isEqualTo(201)
        assertThat(retry.header("X-Idempotency-Replayed")).isNull()
        assertThat(paymentsFor(debtor)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `after the Redis record is gone a different body under the same key is still refused by the database`() {
        val key = UUID.randomUUID().toString()
        val debtor = UUID.randomUUID()
        assertThat(post(key, body(debtor, "1234.56")).statusCode).isEqualTo(201)
        assertThat(requestHashOf(key)).hasSize(64)
        evictRedis(key)

        val reused = post(key, body(debtor, "9999.99"))
        assertThat(reused.statusCode).isEqualTo(409)
        assertThat(reused.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(paymentsFor(debtor)).isEqualTo(1)
        assertThat(amountsFor(debtor)).containsExactly("1234.56")
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `after the Redis record is gone the same body under the same key returns the existing payment`() {
        val key = UUID.randomUUID().toString()
        val debtor = UUID.randomUUID()
        val first = post(key, body(debtor, "1234.56"))
        assertThat(first.statusCode).isEqualTo(201)
        evictRedis(key)

        val again = post(key, body(debtor, "1234.56"))
        assertThat(again.statusCode).isEqualTo(201)
        assertThat(again.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"))
        assertThat(paymentsFor(debtor)).isEqualTo(1)
    }

    private fun evictRedis(key: String) {
        redis.key().del("idempotency:$key").await().indefinitely()
    }

    private fun requestHashOf(key: String): String? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT request_hash FROM sepa_payments WHERE idempotency_key = ?").use {
            it.setString(1, key)
            it.executeQuery().use { rows -> rows.takeIf { r -> r.next() }?.getString(1) }
        }
    }

    private fun amountsFor(debtor: UUID): List<String> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT amount FROM sepa_payments WHERE debtor_account_id = ?").use {
            it.setObject(1, debtor)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getBigDecimal(1).stripTrailingZeros().toPlainString()) }
            }
        }
    }

    private fun post(key: String, json: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .body(json)
        .post("/api/v1/sepa-payments")

    private fun body(debtor: UUID, amount: String) = """
        {
          "type": "SCT",
          "debtorAccountId": "$debtor",
          "debtorIban": "CZ6508000000192000145399",
          "debtorName": "Alice Example",
          "creditorIban": "DE89370400440532013000",
          "creditorName": "Berlin Utility",
          "creditorBic": "COBADEFFXXX",
          "amount": $amount,
          "currency": "EUR",
          "remittanceInfo": "Utility bill",
          "endToEndId": null
        }
    """.trimIndent()

    private fun paymentsFor(debtor: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM sepa_payments WHERE debtor_account_id = ?").use {
            it.setObject(1, debtor)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000010916"
    }
}
