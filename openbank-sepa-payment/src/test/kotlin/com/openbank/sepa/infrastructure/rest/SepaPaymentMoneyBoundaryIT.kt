// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest

import com.openbank.sepa.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID

/**
 * The inbound boundary builds a kernel `Money` (#11642) from `amount` + `currency`, so an amount
 * that cannot be held at the currency's minor-unit scale, or a currency that is not an ISO 4217
 * code with a minor unit, is refused with 400 and a specific code BEFORE any idempotency record,
 * row, outbox event or downstream call exists. On main before this change every one of these was
 * accepted (201) and persisted. Since #11814 the refusal is the kernel's `InvalidMoneyException`,
 * rendered by libs-runtime's `InvalidMoneyExceptionMapper`; the exact problem body is pinned below.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class SepaPaymentMoneyBoundaryIT {

    private companion object {
        val VOLATILE = setOf("instance", "correlationId", "traceId", "timestamp")
        const val UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    }

    @ParameterizedTest(name = "{0} {1} -> 400 {2}")
    @CsvSource(
        "1.005, EUR, AMOUNT_SCALE_EXCEEDED",
        "10.001, EUR, AMOUNT_SCALE_EXCEEDED",
        "0.0001, EUR, AMOUNT_SCALE_EXCEEDED",
        "1.5, JPY, AMOUNT_SCALE_EXCEEDED",
        "10.00, XYZ, CURRENCY_UNSUPPORTED",
        "10.00, EU, CURRENCY_UNSUPPORTED",
        "10.00, EURO, CURRENCY_UNSUPPORTED",
        "10.00, XAU, CURRENCY_UNSUPPORTED",
        "10.00, '', CURRENCY_UNSUPPORTED",
        "1E+19, EUR, VALIDATION_ERROR",
        "123456789012345678901, EUR, VALIDATION_ERROR",
    )
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an amount or currency Money cannot hold is a 400 with a specific code`(
        amount: String,
        currency: String,
        code: String,
    ) {
        val body: Map<String, Any?> = Given {
            contentType("application/json")
            header("Idempotency-Key", "money-boundary-${UUID.randomUUID()}")
            body(body(amount, currency))
        } When {
            post("/api/v1/sepa-payments")
        } Then {
            statusCode(400)
        } Extract {
            jsonPath().getMap("")
        }

        // The whole body, field by field: the kernel InvalidMoneyExceptionMapper (#11814) renders it,
        // and every stable member is pinned so a change to the problem shape is a red test, not a
        // silent contract drift. Only the per-occurrence ids and the timestamp vary.
        val expected = expectedProblem(currency, code)
        val occurrence = body["correlationId"] as String
        assertThat(occurrence).matches(UUID_RE)
        assertThat(body["timestamp"] as String).isNotBlank()
        assertThat(body - VOLATILE).isEqualTo(expected)
        assertThat(body["instance"]).isEqualTo("urn:openbank:error-occurrence:$occurrence")
        assertThat(body["traceId"]).isEqualTo(occurrence)
    }

    private fun expectedProblem(currency: String, code: String): Map<String, Any?> {
        val p = when (code) {
            "AMOUNT_SCALE_EXCEEDED" -> {
                val digits = if (currency == "JPY") 0 else 2
                Expected(
                    "urn:openbank:error:amount-scale-exceeded",
                    "The amount has more decimal places than its currency allows",
                    "amount",
                    "Amount must have at most $digits decimal places for $currency",
                )
            }
            "CURRENCY_UNSUPPORTED" -> Expected(
                "urn:openbank:error:currency-unsupported",
                "The currency is not supported",
                "currency",
                "Currency must be an ISO 4217 code with a minor unit",
            )
            else -> Expected(
                "urn:openbank:error:validation-error",
                "The request is not valid",
                "amount",
                "Amount is out of the supported range",
            )
        }
        val (field, message) = p.field to p.message
        return mapOf(
            "type" to p.type,
            "title" to p.title,
            "status" to 400,
            "detail" to "$field: $message",
            "code" to code,
            "retryable" to false,
            "violations" to listOf(mapOf("field" to field, "message" to message, "code" to code)),
            "message" to "$field: $message",
            "details" to listOf(mapOf("field" to field, "message" to message, "rejectedValue" to null)),
        )
    }

    @ParameterizedTest(name = "{0} {1} is not refused by the Money boundary")
    @CsvSource("10.00, EUR", "10, EUR", "10.5, EUR", "10.50, eur", "10.500, EUR")
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `amounts Money can hold exactly - including lower-case codes - pass the boundary`(
        amount: String,
        currency: String,
    ) {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "money-boundary-ok-${UUID.randomUUID()}")
            body(body(amount, currency))
        } When {
            post("/api/v1/sepa-payments")
        } Then {
            statusCode(not(equalTo(400)))
        }
    }

    private data class Expected(val type: String, val title: String, val field: String, val message: String)

    private fun body(amount: String, currency: String) =
        """
        {"type":"SCT","debtorAccountId":"${UUID.randomUUID()}",
         "debtorIban":"DE89370400440532013000","debtorName":"Alice Example",
         "creditorIban":"FR1420041010050500013M02606","creditorName":"Bob Example",
         "creditorBic":"BNPAFRPP","amount":$amount,"currency":"$currency",
         "remittanceInfo":"money boundary","endToEndId":null}
        """.trimIndent()
}
