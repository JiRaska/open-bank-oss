// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import io.quarkus.test.common.QuarkusTestResource
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
 * real REST endpoint with the real Redis store: a same-body retry replays, a different body under
 * the same key is refused with 422 and creates nothing, and a retry whose JSON differs only in key
 * order and whitespace still replays (the fingerprint is taken over the canonicalised DTO).
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
class SepaPaymentIdempotencyFingerprintIT {

    @Inject
    lateinit var dataSource: DataSource

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
    fun `same key and a different body is refused with 422 and creates nothing`() {
        val key = UUID.randomUUID().toString()
        val debtor = UUID.randomUUID()
        assertThat(post(key, body(debtor, "1234.56")).statusCode).isEqualTo(201)

        val reused = post(key, body(debtor, "9999.99"))
        assertThat(reused.statusCode).isEqualTo(422)
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
