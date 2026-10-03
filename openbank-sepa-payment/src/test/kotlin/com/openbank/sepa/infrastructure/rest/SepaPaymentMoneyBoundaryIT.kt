// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest

import com.openbank.sepa.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
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
 * accepted (201) and persisted.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class SepaPaymentMoneyBoundaryIT {

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
    )
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an amount or currency Money cannot hold is a 400 with a specific code`(
        amount: String,
        currency: String,
        code: String,
    ) {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "money-boundary-${UUID.randomUUID()}")
            body(body(amount, currency))
        } When {
            post("/api/v1/sepa-payments")
        } Then {
            statusCode(400)
            body("code", equalTo(code))
            body("violations[0].code", equalTo(code))
        }
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

    private fun body(amount: String, currency: String) =
        """
        {"type":"SCT","debtorAccountId":"${UUID.randomUUID()}",
         "debtorIban":"DE89370400440532013000","debtorName":"Alice Example",
         "creditorIban":"FR1420041010050500013M02606","creditorName":"Bob Example",
         "creditorBic":"BNPAFRPP","amount":$amount,"currency":"$currency",
         "remittanceInfo":"money boundary","endToEndId":null}
        """.trimIndent()
}
