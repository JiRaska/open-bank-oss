// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.path.json.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.util.UUID

/**
 * #11604: every balance write builds a kernel Money (`Money.parseInbound`) BEFORE its idempotency
 * lookup — `(accountId, currency, referenceId)` for a hold (ADR-0287), the `referenceId` movement
 * marker for credit/debit (V8). An amount or currency Money cannot hold is a 400 problem rendered by
 * libs-runtime's InvalidMoneyExceptionMapper, naming the field and never echoing the value. Nothing
 * is reserved, booked, marked or announced, so the referenceId stays free and a corrected retry
 * applies.
 *
 * On main before this change every refusal row below was a 2xx: `10.005 EUR` was reserved/booked
 * as a sub-cent figure, `1000.5 JPY` as a fractional yen, an unknown currency created a pocket, and
 * a NEGATIVE credit lowered the booked balance with no overdraft guard.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
class BalanceMoneyBoundaryIT {

    private companion object {
        const val USER = "00000000-0000-0000-0000-000000000099"
        val VOLATILE = setOf("instance", "correlationId", "traceId", "timestamp")
        const val UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    }

    @ParameterizedTest(name = "{0} {1} {2} -> 400 {3}")
    @CsvSource(
        "holds, 10.005, EUR, AMOUNT_SCALE_EXCEEDED, 2",
        "credit, 0.001, CZK, AMOUNT_SCALE_EXCEEDED, 2",
        "debit, 1000.5, JPY, AMOUNT_SCALE_EXCEEDED, 0",
        "credit, 1.2345, KWD, AMOUNT_SCALE_EXCEEDED, 3",
        "holds, 10.00, XYZ, CURRENCY_UNSUPPORTED, 0",
        "credit, 10.00, EURO, CURRENCY_UNSUPPORTED, 0",
        "debit, 10.00, XAU, CURRENCY_UNSUPPORTED, 0",
        "credit, 1E+19, EUR, VALIDATION_ERROR, 0",
    )
    @TestSecurity(user = USER, roles = ["ROLE_API"])
    fun `an amount or currency Money cannot hold is a 400 with the exact problem body and moves nothing`(
        operation: String,
        amount: String,
        currency: String,
        code: String,
        digits: Int,
    ) {
        val pocketCurrency = if (code == "CURRENCY_UNSUPPORTED") "EUR" else currency
        val account = initialize(pocketCurrency, "100")
        val reference = "ref-${UUID.randomUUID()}"

        val raw = Given {
            contentType("application/json")
            body(movement(amount, "\"$currency\"", reference))
        } When {
            post("/api/v1/balances/$account/$operation")
        } Then {
            statusCode(400)
        } Extract {
            body().asString()
        }
        val body: Map<String, Any?> = JsonPath(raw).getMap("")
        val occurrence = body["correlationId"] as String
        assertThat(occurrence).matches(UUID_RE)
        assertThat(body - VOLATILE).isEqualTo(expectedProblem(currency, code, digits))
        assertThat(body["instance"]).isEqualTo("urn:openbank:error-occurrence:$occurrence")
        assertThat(raw).doesNotContain(if (code == "CURRENCY_UNSUPPORTED") "\"$currency\"" else amount)

        // Nothing moved on the pocket ...
        val pocket = balance(account, pocketCurrency)
        assertThat(BigDecimal(pocket.getString("bookedAmount"))).isEqualByComparingTo("100")
        assertThat(BigDecimal(pocket.getString("reservedAmount"))).isEqualByComparingTo("0")
        assertThat(BigDecimal(pocket.getString("availableAmount"))).isEqualByComparingTo("100")
        // ... no pocket appeared for the refused currency ...
        if (code == "CURRENCY_UNSUPPORTED") {
            Given { this } When { get("/api/v1/balances/$account/$currency") } Then { statusCode(404) }
        }
        // ... and the referenceId (idempotency key) is free: a valid retry with it applies.
        val retry = Given {
            contentType("application/json")
            body(movement("1", "\"$pocketCurrency\"", reference))
        } When {
            post("/api/v1/balances/$account/$operation")
        } Then {
            statusCode(if (operation == "holds") 201 else 200)
        } Extract { body().jsonPath() }
        val expectedBooked = when (operation) {
            "credit" -> "101"
            "debit" -> "99"
            else -> "100"
        }
        assertThat(BigDecimal(balance(account, pocketCurrency).getString("bookedAmount")))
            .isEqualByComparingTo(expectedBooked)
        if (operation == "holds") assertThat(retry.getString("referenceId")).isEqualTo(reference)
    }

    @ParameterizedTest(name = "{0} of {1} -> 400 naming amount")
    @CsvSource("credit, -5.00", "debit, -5.00", "holds, -5.00", "credit, 0", "debit, 0.00", "holds, 0")
    @TestSecurity(user = USER, roles = ["ROLE_API"])
    fun `a hold, credit or debit of zero or less is refused and moves nothing`(operation: String, amount: String) {
        val account = initialize("CZK", "100")
        Given {
            contentType("application/json")
            body(movement(amount, "\"CZK\"", "neg-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/balances/$account/$operation")
        } Then {
            statusCode(400)
            body("code", equalTo("VALIDATION_ERROR"))
            body("violations[0].field", equalTo("amount"))
        }
        val pocket = balance(account, "CZK")
        assertThat(BigDecimal(pocket.getString("bookedAmount"))).isEqualByComparingTo("100")
        assertThat(BigDecimal(pocket.getString("availableAmount"))).isEqualByComparingTo("100")
        assertThat(BigDecimal(pocket.getString("reservedAmount"))).isEqualByComparingTo("0")
    }

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_API"])
    fun `an absent amount or currency is a 400 naming the field`() {
        val account = initialize("CZK", "100")
        Given {
            contentType("application/json")
            body("""{"currency":"CZK","referenceId":"r-${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/balances/$account/credit")
        } Then {
            statusCode(400)
            body("violations[0].field", equalTo("amount"))
        }
        Given {
            contentType("application/json")
            body("""{"amount":"1.00","referenceId":"r-${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/balances/$account/debit")
        } Then {
            statusCode(400)
            body("violations[0].field", equalTo("currency"))
        }
    }

    @ParameterizedTest(name = "initialize {0} {1} -> 400 {2} on {3}")
    @CsvSource(
        "XYZ, 0, CURRENCY_UNSUPPORTED, currency",
        "EUR, 1.001, AMOUNT_SCALE_EXCEEDED, initialAmount",
        "JPY, 0.5, AMOUNT_SCALE_EXCEEDED, initialAmount",
    )
    @TestSecurity(user = USER, roles = ["ROLE_API"])
    fun `initialize refuses what Money cannot hold and creates no pocket`(
        currency: String,
        initialAmount: String,
        code: String,
        field: String,
    ) {
        val account = UUID.randomUUID()
        Given {
            contentType("application/json")
            body("""{"currency":"$currency","initialAmount":$initialAmount}""")
        } When {
            post("/api/v1/balances/$account/initialize")
        } Then {
            statusCode(400)
            body("code", equalTo(code))
            body("violations[0].field", equalTo(field))
        }
        Given { this } When { get("/api/v1/balances/$account") } Then {
            statusCode(200)
            body("balances.size()", equalTo(0))
        }
    }

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_SUPERVISOR", "ROLE_API"])
    fun `an over-scale or negative overdraft limit is refused`() {
        val account = initialize("CZK", "0")
        for ((limit, code) in listOf("100.001" to "AMOUNT_SCALE_EXCEEDED", "-1.00" to "VALIDATION_ERROR")) {
            Given {
                contentType("application/json")
                body("""{"arrangedOverdraftLimit":$limit}""")
            } When {
                put("/api/v1/balances/$account/CZK/overdraft-limit")
            } Then {
                statusCode(400)
                body("code", equalTo(code))
                body("violations[0].field", equalTo("arrangedOverdraftLimit"))
            }
        }
        Given {
            contentType("application/json")
            body("""{"arrangedOverdraftLimit":500}""")
        } When {
            put("/api/v1/balances/$account/CZK/overdraft-limit")
        } Then {
            statusCode(200)
        }
    }

    @ParameterizedTest(name = "hold of {0} {1} is accepted and served as {2} {3}")
    @CsvSource(
        "10.5, EUR, 10.50, EUR",
        "10.5000, EUR, 10.50, EUR",
        "250, CZK, 250.00, CZK",
        "10.00, eur, 10.00, EUR",
        "1000, JPY, 1000, JPY",
    )
    @TestSecurity(user = USER, roles = ["ROLE_API"])
    fun `valid input is accepted at the currency's own scale`(
        amount: String,
        currency: String,
        servedAmount: String,
        servedCurrency: String,
    ) {
        val account = initialize(servedCurrency, "5000")
        val raw = Given {
            contentType("application/json")
            body(movement(amount, "\"$currency\"", "ok-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/balances/$account/holds")
        } Then {
            statusCode(201)
            body("currency", equalTo(servedCurrency))
        } Extract {
            body().asString()
        }
        assertThat(raw).contains("\"amount\":$servedAmount,")
        assertThat(BigDecimal(balance(account, servedCurrency).getString("reservedAmount")))
            .isEqualByComparingTo(servedAmount)
    }

    private fun initialize(currency: String, amount: String): UUID {
        val account = UUID.randomUUID()
        Given {
            contentType("application/json")
            body("""{"currency":"$currency","initialAmount":$amount}""")
        } When {
            post("/api/v1/balances/$account/initialize")
        } Then {
            statusCode(201)
        }
        return account
    }

    private fun balance(account: UUID, currency: String): JsonPath =
        Given { this } When { get("/api/v1/balances/$account/$currency") } Then { statusCode(200) } Extract {
            body().jsonPath()
        }

    private fun movement(amount: String, currencyJson: String, reference: String) =
        """{"amount":$amount,"currency":$currencyJson,"reason":"money-boundary","referenceId":"$reference"}"""

    private fun expectedProblem(currency: String, code: String, digits: Int): Map<String, Any?> {
        val (field, message) = when (code) {
            "AMOUNT_SCALE_EXCEEDED" -> "amount" to "Amount must have at most $digits decimal places for $currency"
            "CURRENCY_UNSUPPORTED" -> "currency" to "Currency must be an ISO 4217 code with a minor unit"
            else -> "amount" to "Amount is out of the supported range"
        }
        val (type, title) = when (code) {
            "AMOUNT_SCALE_EXCEEDED" ->
                "urn:openbank:error:amount-scale-exceeded" to
                    "The amount has more decimal places than its currency allows"
            "CURRENCY_UNSUPPORTED" -> "urn:openbank:error:currency-unsupported" to "The currency is not supported"
            else -> "urn:openbank:error:validation-error" to "The request is not valid"
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
}
