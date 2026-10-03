// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.integration

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
 * #11604: `POST /api/v1/sepa-instant` builds the kernel `Money` from `amount` + `currency` at the
 * boundary, BEFORE the idempotency key is looked up or any row, event, screening or downstream
 * call exists. On main before this change the over-scale and unknown-currency cases were accepted
 * (201, persisted at NUMERIC(20,6)) and the over-long / out-of-range ones failed at the database
 * flush as a 500. The refusal is the kernel `InvalidMoneyException`, rendered by libs-runtime's
 * `InvalidMoneyExceptionMapper`; the exact problem body is pinned below.
 *
 * #11913: SCT Inst is euro-only, so a currency Money CAN hold but the scheme does not carry (USD,
 * CZK, GBP) is refused at the same boundary as 400 CURRENCY_NOT_ALLOWED (ADR-0326 domain code,
 * rendered by libs-runtime's `DomainExceptionMapper`). On main before that change those were 201.
 */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_it")],
)
class SctInstMoneyBoundaryIT {

    private companion object {
        val VOLATILE = setOf("instance", "correlationId", "traceId", "timestamp")
        const val UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        const val OPERATOR = "00000000-0000-0000-0000-000000000099"
    }

    @ParameterizedTest(name = "{0} {1} -> 400 {2}")
    @CsvSource(
        "1.005, EUR, AMOUNT_SCALE_EXCEEDED",
        "10.001, EUR, AMOUNT_SCALE_EXCEEDED",
        "0.000001, EUR, AMOUNT_SCALE_EXCEEDED",
        "10.00, XYZ, CURRENCY_UNSUPPORTED",
        "10.00, EURO, CURRENCY_UNSUPPORTED",
        "10.00, XAU, CURRENCY_UNSUPPORTED",
        "10.00, '', CURRENCY_UNSUPPORTED",
        "10.00, '   ', CURRENCY_UNSUPPORTED",
        "1E+19, EUR, VALIDATION_ERROR",
        "123456789012345678901, EUR, VALIDATION_ERROR",
        // #11913: a valid ISO currency that SCT Inst (euro-only) does not carry.
        "10.00, USD, CURRENCY_NOT_ALLOWED",
        "10.00, CZK, CURRENCY_NOT_ALLOWED",
        "10.00, gbp, CURRENCY_NOT_ALLOWED",
    )
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an amount or currency Money cannot hold is a 400 with the exact problem body`(
        amount: String,
        currency: String,
        code: String,
    ) {
        val debtor = UUID.randomUUID()
        val key = "money-boundary-${UUID.randomUUID()}"
        val body: Map<String, Any?> = Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(payload(key, debtor, amount, "\"$currency\""))
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(400)
        } Extract {
            jsonPath().getMap("")
        }

        val occurrence = body["correlationId"] as String
        assertThat(occurrence).matches(UUID_RE)
        assertThat(body["timestamp"] as String).isNotBlank()
        assertThat(body - VOLATILE).isEqualTo(expectedProblem(currency, code))
        assertThat(body["instance"]).isEqualTo("urn:openbank:error-occurrence:$occurrence")
        assertThat(body["traceId"]).isEqualTo(occurrence)

        // Nothing was persisted for this debtor ...
        Given { this } When { get("/api/v1/sepa-instant/debtor/$debtor") } Then {
            statusCode(200)
            body("size()", equalTo(0))
        }
        // ... and the key was never reserved: the same key with a valid amount creates the payment.
        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(payload(key, debtor, "10.00", "\"EUR\""))
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(201)
            body("amount", equalTo(10.0f))
        }
    }

    @ParameterizedTest(name = "{0} {1} is accepted and served as {2} {3}")
    @CsvSource(
        "99.99, EUR, 99.99, EUR",
        "10.500, EUR, 10.50, EUR",
        "10, EUR, 10.00, EUR",
        "10.00, eur, 10.00, EUR",
        "10.00, ' EUR ', 10.00, EUR",
    )
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `valid input is accepted and the response carries the currency scale`(
        amount: String,
        currency: String,
        servedAmount: String,
        servedCurrency: String,
    ) {
        val key = "money-ok-${UUID.randomUUID()}"
        val raw = Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(payload(key, UUID.randomUUID(), amount, "\"$currency\""))
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(201)
            body("currency", equalTo(servedCurrency))
        } Extract {
            body().asString()
        }
        assertThat(raw).contains("\"amount\":$servedAmount,")
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an omitted currency still defaults to EUR`() {
        val key = "money-default-${UUID.randomUUID()}"
        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(payload(key, UUID.randomUUID(), "12.34", null))
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(201)
            body("currency", equalTo("EUR"))
        }
    }

    private fun expectedProblem(currency: String, code: String): Map<String, Any?> {
        val parts = when (code) {
            "AMOUNT_SCALE_EXCEEDED" -> listOf(
                "urn:openbank:error:amount-scale-exceeded",
                "The amount has more decimal places than its currency allows",
                "amount",
                "Amount must have at most 2 decimal places for ${currency.trim()}",
            )
            "CURRENCY_UNSUPPORTED" -> listOf(
                "urn:openbank:error:currency-unsupported",
                "The currency is not supported",
                "currency",
                "Currency must be an ISO 4217 code with a minor unit",
            )
            "CURRENCY_NOT_ALLOWED" -> listOf(
                "urn:openbank:error:currency-not-allowed",
                "The currency is not allowed by the payment scheme",
                "currency",
                "SCT Inst accepts EUR only",
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

    private fun payload(key: String, debtor: UUID, amount: String, currencyJson: String?): String {
        val currency = currencyJson?.let { "\"currency\":$it," } ?: ""
        return """
            {"idempotencyKey":"$key","debtorAccountId":"$debtor",
             "debtorIban":"CZ6508000000192000145399","debtorName":"Test Debtor",
             "creditorIban":"DE89370400440532013000","creditorName":"Test Creditor",
             "creditorBic":"COBADEFFXXX","amount":$amount,$currency
             "remittanceInfo":"money boundary","endToEndId":"E2E-${UUID.randomUUID().toString().take(20)}"}
        """.trimIndent()
    }
}
