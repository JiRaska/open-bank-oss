// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID

/**
 * #11604: `POST /api/v1/clearing/submit` builds a kernel Money (`Money.parseInbound`) BEFORE the
 * paymentId — the endpoint's idempotency key (ADR-0298) — is looked up. An amount or currency
 * Money cannot hold is a 400 problem rendered by libs-runtime's InvalidMoneyExceptionMapper,
 * naming the field and never echoing the value; no clearing item is written, so the paymentId
 * stays free and a corrected retry creates the item.
 *
 * On main before this change every refusal row below was a 201 that persisted a PENDING item a
 * clearing cycle would net into a batch (or, for the out-of-range rows, a 500 at INSERT).
 *
 * Clearing carries several rails and no currency allow-list, so JPY (0 dp) and KWD (3 dp) are
 * valid and each is held at its own minor unit.
 */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_clearing_money_it")],
)
class ClearingMoneyBoundaryIT {

    private companion object {
        val VOLATILE = setOf("instance", "correlationId", "traceId", "timestamp")
        const val UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        const val USER = "00000000-0000-0000-0000-000000000099"
    }

    @ParameterizedTest(name = "{0} {1} -> 400 {2}")
    @CsvSource(
        "100.505, EUR, AMOUNT_SCALE_EXCEEDED, 2",
        "0.001, CZK, AMOUNT_SCALE_EXCEEDED, 2",
        "1000.5, JPY, AMOUNT_SCALE_EXCEEDED, 0",
        "1.2345, KWD, AMOUNT_SCALE_EXCEEDED, 3",
        "10.00, XYZ, CURRENCY_UNSUPPORTED, 0",
        "10.00, EURO, CURRENCY_UNSUPPORTED, 0",
        "10.00, XAU, CURRENCY_UNSUPPORTED, 0",
        "10.00, '', CURRENCY_UNSUPPORTED, 0",
        "1E+19, EUR, VALIDATION_ERROR, 0",
        "123456789012345678901, EUR, VALIDATION_ERROR, 0",
    )
    @TestSecurity(user = USER, roles = ["ROLE_PAYMENTS"])
    fun `an amount or currency Money cannot hold is a 400 with the exact problem body`(
        amount: String,
        currency: String,
        code: String,
        digits: Int,
    ) {
        val paymentId = UUID.randomUUID()
        val raw = Given {
            contentType("application/json")
            body(payload(paymentId, amount, "\"$currency\""))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(400)
        } Extract {
            body().asString()
        }
        val body: Map<String, Any?> = io.restassured.path.json.JsonPath(raw).getMap("")

        val occurrence = body["correlationId"] as String
        assertThat(occurrence).matches(UUID_RE)
        assertThat(body["timestamp"] as String).isNotBlank()
        assertThat(body - VOLATILE).isEqualTo(expectedProblem(currency, code, digits))
        assertThat(body["instance"]).isEqualTo("urn:openbank:error-occurrence:$occurrence")
        // The rejected value is never echoed.
        assertThat(raw).doesNotContain(amount.takeIf { code != "CURRENCY_UNSUPPORTED" } ?: "\"$currency\"")

        // Nothing was persisted for this payment ...
        Given { this } When { get("/api/v1/clearing/items/by-payment/$paymentId") } Then {
            statusCode(200)
            body("size()", equalTo(0))
        }
        // ... and the paymentId (idempotency key) was not consumed: a valid retry creates the item.
        Given {
            contentType("application/json")
            body(payload(paymentId, "10.00", "\"EUR\""))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(201)
            body("paymentId", equalTo(paymentId.toString()))
            body("currency", equalTo("EUR"))
        }
        Given { this } When { get("/api/v1/clearing/items/by-payment/$paymentId") } Then {
            statusCode(200)
            body("size()", equalTo(1))
        }
    }

    @ParameterizedTest(name = "{0} {1} is accepted and served as {2} {3}")
    @CsvSource(
        "100.50, EUR, 100.50, EUR",
        "100.5, EUR, 100.50, EUR",
        "100.5000, EUR, 100.50, EUR",
        "250, CZK, 250.00, CZK",
        "10.00, eur, 10.00, EUR",
        "1000, JPY, 1000, JPY",
        "1.500, KWD, 1.500, KWD",
    )
    @TestSecurity(user = USER, roles = ["ROLE_PAYMENTS"])
    fun `valid input is accepted at the currency's own scale`(
        amount: String,
        currency: String,
        servedAmount: String,
        servedCurrency: String,
    ) {
        val raw = Given {
            contentType("application/json")
            body(payload(UUID.randomUUID(), amount, "\"$currency\""))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(201)
            body("currency", equalTo(servedCurrency))
        } Extract {
            body().asString()
        }
        assertThat(raw).contains("\"amount\":$servedAmount,")
    }

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_PAYMENTS"])
    fun `an omitted currency still defaults to EUR`() {
        Given {
            contentType("application/json")
            body(payload(UUID.randomUUID(), "12.34", null))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(201)
            body("currency", equalTo("EUR"))
        }
    }

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_PAYMENTS"])
    fun `an omitted amount is a 400 naming the field`() {
        val paymentId = UUID.randomUUID()
        Given {
            contentType("application/json")
            body(payload(paymentId, null, "\"EUR\""))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(400)
            body("violations[0].field", equalTo("amount"))
        }
        Given { this } When { get("/api/v1/clearing/items/by-payment/$paymentId") } Then {
            body("size()", equalTo(0))
        }
    }

    private fun expectedProblem(currency: String, code: String, digits: Int): Map<String, Any?> {
        val parts = when (code) {
            "AMOUNT_SCALE_EXCEEDED" -> listOf(
                "urn:openbank:error:amount-scale-exceeded",
                "The amount has more decimal places than its currency allows",
                "amount",
                "Amount must have at most $digits decimal places for $currency",
            )
            "CURRENCY_UNSUPPORTED" -> listOf(
                "urn:openbank:error:currency-unsupported",
                "The currency is not supported",
                "currency",
                "Currency must be an ISO 4217 code with a minor unit",
            )
            else -> listOf(
                "urn:openbank:error:validation-error",
                "The request is not valid",
                "amount",
                "Amount is out of the supported range",
            )
        }
        val (type, title) = parts[0] to parts[1]
        val (field, message) = parts[2] to parts[3]
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

    private fun payload(paymentId: UUID, amount: String?, currencyJson: String?): String {
        val currency = currencyJson?.let { "\"currency\":$it," } ?: ""
        val amountField = amount?.let { "\"amount\":$it," } ?: ""
        return """
            {"paymentId":"$paymentId","paymentReference":"MB-$paymentId",
             "debtorIban":"CZ6508000000192000145399","creditorIban":"DE89370400440532013000",
             $amountField $currency "rail":"SEPA_SCT"}
        """.trimIndent()
    }
}
