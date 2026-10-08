// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /api/v1/domestic-payments` builds a kernel `Money` from `amount` + `currency` (#11604) and
 * requires it to be strictly positive and CZK-only (#12059), BEFORE the Idempotency-Key is bound. On main
 * before this change every refused case below answered 201 and persisted a payment, an outbox
 * event and a started workflow — including negative and zero amounts and sub-haléř amounts that the
 * settlement adapter later rounded. Each refusal is pinned as the whole problem body, and is shown
 * to leave no row, no outbox event and the Idempotency-Key free for a valid retry.
 */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
class DomesticPaymentMoneyBoundaryIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_VIEWER"])
    fun `a viewer cannot create a domestic payment or consume an idempotency key`() {
        val key = "money-boundary-it-denied-${UUID.randomUUID()}"

        val refused = post(key, body("10.00", "CZK"))

        assertThat(refused.statusCode).isEqualTo(403)
        assertThat(countPayments(key)).isZero()
    }

    @ParameterizedTest(name = "{0} {1} -> 400 {2}")
    @CsvSource(
        "100.005, CZK, AMOUNT_SCALE_EXCEEDED, amount",
        "0.001, CZK, AMOUNT_SCALE_EXCEEDED, amount",
        "10.00, XYZ, CURRENCY_UNSUPPORTED, currency",
        "10.00, CZ, CURRENCY_UNSUPPORTED, currency",
        "10.00, XAU, CURRENCY_UNSUPPORTED, currency",
        "10.00, EUR, CURRENCY_NOT_ALLOWED, currency",
        "10.00, USD, CURRENCY_NOT_ALLOWED, currency",
        "10.00, gbp, CURRENCY_NOT_ALLOWED, currency",
        "1E+19, CZK, VALIDATION_ERROR, amount",
        "-5.00, CZK, VALIDATION_ERROR, amount",
        "0, CZK, VALIDATION_ERROR, amount",
        "0.00, CZK, VALIDATION_ERROR, amount",
    )
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `an invalid amount or a currency outside the domestic scheme is refused and consumes nothing`(
        amount: String,
        currency: String,
        code: String,
        field: String,
    ) {
        val key = "money-boundary-it-${UUID.randomUUID()}"

        val refused = post(key, body(amount, currency))

        assertThat(refused.statusCode).isEqualTo(400)
        val problem: Map<String, Any?> = refused.jsonPath().getMap("")
        assertThat(problem - VOLATILE).isEqualTo(expectedProblem(code, field, amount, currency))
        assertThat(countPayments(key)).isZero()

        // The key was never bound: a valid request under the same key is a fresh create, not a 409.
        val retry = post(key, body("10.00", "CZK"))
        assertThat(retry.statusCode).isEqualTo(201)
        assertThat(retry.header("X-Idempotency-Replayed")).isNull()
        assertThat(countPayments(key)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `an absent amount is a 400 naming the field`() {
        val key = "money-boundary-it-absent-${UUID.randomUUID()}"
        val response = post(key, body(null, "CZK"))

        assertThat(response.statusCode).isEqualTo(400)
        assertThat(countPayments(key)).isZero()
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `a valid amount is stored unchanged and read back at the currency scale`() {
        val key = "money-boundary-it-ok-${UUID.randomUUID()}"

        val debtor = UUID.randomUUID()
        val created = post(key, body("100.5", " czk ", debtor))

        assertThat(created.statusCode).isEqualTo(201)
        assertThat(created.jsonPath().getString("amount")).isEqualTo("100.50")
        assertThat(created.jsonPath().getString("currency")).isEqualTo("CZK")
        assertThat(storedAmount(key)).isEqualByComparingTo(BigDecimal("100.5"))
        val read = RestAssured.given().get("/api/v1/domestic-payments/${created.jsonPath().getString("id")}")
        assertThat(read.jsonPath().getString("amount")).isEqualTo("100.50")

        // Trailing zeros do not change the binding: the same key with 100.500 is an exact replay.
        val replay = post(key, body("100.500", "CZK", debtor))
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
    }

    private fun expectedProblem(code: String, field: String, amount: String, currency: String): Map<String, Any?> {
        val (type, title, message) = when (code) {
            "AMOUNT_SCALE_EXCEEDED" -> Triple(
                "urn:openbank:error:amount-scale-exceeded",
                "The amount has more decimal places than its currency allows",
                "Amount must have at most 2 decimal places for $currency",
            )
            "CURRENCY_UNSUPPORTED" -> Triple(
                "urn:openbank:error:currency-unsupported",
                "The currency is not supported",
                "Currency must be an ISO 4217 code with a minor unit",
            )
            "CURRENCY_NOT_ALLOWED" -> Triple(
                "urn:openbank:error:currency-not-allowed",
                "The currency is not allowed by the payment scheme",
                "Czech domestic payments accept CZK only",
            )
            else -> Triple(
                "urn:openbank:error:validation-error",
                "The request is not valid",
                if (amount == "1E+19") RANGE else POSITIVE,
            )
        }
        if (message == POSITIVE) {
            // A non-positive amount is a valid Money, so it is refused by requireValid (a
            // ValidationFailure), whose problem body carries no field prefix and no per-violation code.
            return mapOf(
                "type" to type,
                "title" to title,
                "status" to 400,
                "detail" to message,
                "code" to code,
                "retryable" to false,
                "violations" to listOf(mapOf("field" to field, "message" to message)),
                "message" to message,
                "details" to listOf(mapOf("field" to field, "message" to message, "rejectedValue" to null)),
            )
        }
        return mapOf(
            "type" to type,
            "title" to title,
            "status" to 400,
            "detail" to "$field: $message",
            "code" to code,
            "retryable" to false,
            "violations" to listOf(mapOf("field" to field, "message" to message, "code" to code)),
            "message" to "$field: $message",
            "details" to listOf(mapOf("field" to field, "message" to message, "rejectedValue" to null)),
        )
    }

    private fun post(key: String, body: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .body(body)
        .post("/api/v1/domestic-payments")

    private fun body(amount: String?, currency: String, debtorAccountId: UUID = UUID.randomUUID()): String =
        """
        {
          "debtorAccountId": "$debtorAccountId",
          "debtorAccountNumber": "1234567890",
          "debtorBankCode": "0800",
          "debtorName": "Alice Example",
          "creditorAccountNumber": "9876543210",
          "creditorBankCode": "0100",
          "creditorName": "Brno Utility",
          ${if (amount == null) "" else "\"amount\": $amount,"}
          "currency": "$currency",
          "variableSymbol": null,
          "specificSymbol": null,
          "constantSymbol": null,
          "messageForPayee": null,
          "priority": "STANDARD",
          "statementLabel": null,
          "endToEndId": null
        }
        """.trimIndent()

    private fun countPayments(key: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM domestic_payments WHERE idempotency_key = ?").use {
            it.setString(1, key)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun storedAmount(key: String): BigDecimal = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT amount FROM domestic_payments WHERE idempotency_key = ?").use {
            it.setString(1, key)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getBigDecimal(1)
            }
        }
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000077"
        val VOLATILE = setOf("instance", "correlationId", "traceId", "timestamp")
        const val RANGE = "Amount is out of the supported range"
        const val POSITIVE = "amount must be greater than zero"
    }
}
