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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import javax.sql.DataSource

/**
 * #11931: SEPA Credit Transfer is a euro-only scheme. A currency kernel `Money` CAN hold but SCT
 * does not carry (USD, CZK, GBP) is refused at the REST boundary as 400 CURRENCY_NOT_ALLOWED — the
 * same wire contract as sepa-instant (#11913) — before the Idempotency-Key is reserved, so nothing
 * is persisted, no outbox event exists and the key stays free for a corrected retry. On main before
 * this change every one of these was 201 and persisted.
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
class SepaPaymentSchemeCurrencyIT {

    @Inject
    lateinit var dataSource: DataSource

    @ParameterizedTest(name = "{0} -> 400 CURRENCY_NOT_ALLOWED, nothing persisted, key not consumed")
    @ValueSource(strings = ["USD", "CZK", "gbp"])
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `a non-EUR currency is refused before anything is persisted or the key is consumed`(currency: String) {
        val key = "sct-eur-only-${UUID.randomUUID()}"
        val debtor = UUID.randomUUID()
        val outboxBefore = count("SELECT count(*) FROM sepa_payment_outbox")

        val refused = post(key, body(debtor, currency))

        assertThat(refused.statusCode).isEqualTo(400)
        val problem: Map<String, Any?> = refused.jsonPath().getMap("")
        val occurrence = problem["correlationId"] as String
        assertThat(occurrence).matches(UUID_RE)
        assertThat(problem["timestamp"] as String).isNotBlank()
        assertThat(problem["instance"]).isEqualTo("urn:openbank:error-occurrence:$occurrence")
        assertThat(problem["traceId"]).isEqualTo(occurrence)
        assertThat(problem - VOLATILE).isEqualTo(EXPECTED_PROBLEM)
        // The rejected value is never echoed, in any case spelling.
        assertThat(refused.asString()).doesNotContain(currency).doesNotContain(currency.uppercase())

        assertThat(count("SELECT count(*) FROM sepa_payments WHERE debtor_account_id = '$debtor'")).isEqualTo(0)
        assertThat(count("SELECT count(*) FROM sepa_payments WHERE idempotency_key = '$key'")).isEqualTo(0)
        assertThat(count("SELECT count(*) FROM sepa_payment_outbox")).isEqualTo(outboxBefore)

        // The key was never reserved: the corrected EUR request under the SAME key is a fresh 201,
        // not a 409 (mismatch / in progress) and not a replay.
        val retry = post(key, body(debtor, "EUR"))
        assertThat(retry.statusCode).isEqualTo(201)
        assertThat(retry.header("X-Idempotency-Replayed")).isNull()
        assertThat(retry.jsonPath().getString("currency")).isEqualTo("EUR")
        assertThat(count("SELECT count(*) FROM sepa_payments WHERE debtor_account_id = '$debtor'")).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0} -> 201 EUR")
    @ValueSource(strings = ["EUR", "eur"])
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `EUR in any case is accepted`(currency: String) {
        val debtor = UUID.randomUUID()
        val created = post("sct-eur-ok-${UUID.randomUUID()}", body(debtor, currency))
        assertThat(created.statusCode).isEqualTo(201)
        assertThat(created.jsonPath().getString("currency")).isEqualTo("EUR")
        assertThat(count("SELECT count(*) FROM sepa_payments WHERE debtor_account_id = '$debtor'")).isEqualTo(1)
    }

    private fun post(key: String, json: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .body(json)
        .post("/api/v1/sepa-payments")

    private fun body(debtor: UUID, currency: String) = """
        {
          "type": "SCT",
          "debtorAccountId": "$debtor",
          "debtorIban": "CZ6508000000192000145399",
          "debtorName": "Alice Example",
          "creditorIban": "DE89370400440532013000",
          "creditorName": "Berlin Utility",
          "creditorBic": "COBADEFFXXX",
          "amount": 10.00,
          "currency": "$currency",
          "remittanceInfo": "Utility bill",
          "endToEndId": null
        }
    """.trimIndent()

    private fun count(sql: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000011931"
        const val UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val VOLATILE = setOf("instance", "correlationId", "traceId", "timestamp")
        const val MESSAGE = "SCT accepts EUR only"
        val EXPECTED_PROBLEM: Map<String, Any?> = mapOf(
            "type" to "urn:openbank:error:currency-not-allowed",
            "title" to "The currency is not allowed by the payment scheme",
            "status" to 400,
            "detail" to "currency: $MESSAGE",
            "code" to "CURRENCY_NOT_ALLOWED",
            "retryable" to false,
            "violations" to listOf(
                mapOf("field" to "currency", "message" to MESSAGE, "code" to "CURRENCY_NOT_ALLOWED"),
            ),
            "message" to "currency: $MESSAGE",
            "details" to listOf(mapOf("field" to "currency", "message" to MESSAGE, "rejectedValue" to null)),
        )
    }
}
