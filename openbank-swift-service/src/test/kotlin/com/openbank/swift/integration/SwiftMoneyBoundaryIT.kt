// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.integration

import com.openbank.swift.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID
import javax.sql.DataSource

/**
 * #11604: `POST /api/v1/swift` builds a kernel `Money` from `amountMinorUnits` + `currency` BEFORE
 * the idempotency lookup, so an amount or currency `Money` cannot hold is a 400 with a specific code,
 * and leaves no `swift_messages` row, no outbox row and no consumed Idempotency key behind.
 *
 * On main before this change every currency case below answered 201 and was persisted (the
 * currency was any string), `150.5` minor units was silently truncated to `150` by Jackson's
 * float-to-int coercion and persisted, and the out-of-range cases were a Jackson coercion error
 * rather than a coded refusal. SWIFT is cross-border: there is deliberately NO currency allow-list,
 * so JPY (0dp) and KWD/BHD (3dp) pass — see [valid currencies with any minor unit pass].
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class SwiftMoneyBoundaryIT {

    @Inject
    lateinit var dataSource: DataSource

    private companion object {
        val VOLATILE = setOf("instance", "correlationId", "traceId", "timestamp")
        const val UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        const val ACTOR = "00000000-0000-0000-0000-000000000099"
        const val REF_LEN = 12
        const val MIN_ECHO_PROBE = 4
    }

    @ParameterizedTest(name = "{0} {1} -> 400 {2}")
    @CsvSource(
        "150.5, EUR, AMOUNT_SCALE_EXCEEDED, amountMinorUnits",
        "0.1, JPY, AMOUNT_SCALE_EXCEEDED, amountMinorUnits",
        "1000.5, KWD, AMOUNT_SCALE_EXCEEDED, amountMinorUnits",
        "150000, XYZ, CURRENCY_UNSUPPORTED, currency",
        "150000, EU, CURRENCY_UNSUPPORTED, currency",
        "150000, EURO, CURRENCY_UNSUPPORTED, currency",
        "150000, XAU, CURRENCY_UNSUPPORTED, currency",
        "150000, '', CURRENCY_UNSUPPORTED, currency",
        "9223372036854775808, JPY, VALIDATION_ERROR, amountMinorUnits",
        "1E+25, EUR, VALIDATION_ERROR, amountMinorUnits",
    )
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `an amount or currency Money cannot hold is a coded 400 that consumes nothing`(
        amount: String,
        currency: String,
        code: String,
        field: String,
    ) {
        val key = "swift-money-${UUID.randomUUID()}"
        val messagesBefore = count("select count(*) from swift_messages")
        val outboxBefore = count("select count(*) from swift_outbox")

        val body: Map<String, Any?> = Given {
            contentType("application/json")
            body(body(key, amount, currency))
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(400)
        } Extract {
            jsonPath().getMap("")
        }

        val occurrence = body["correlationId"] as String
        assertThat(occurrence).matches(UUID_RE)
        assertThat(body["timestamp"] as String).isNotBlank()
        assertThat(body - VOLATILE).isEqualTo(expectedProblem(code, field))
        assertThat(body["instance"]).isEqualTo("urn:openbank:error-occurrence:$occurrence")
        assertThat(body["traceId"]).isEqualTo(occurrence)
        // The rejected value is never echoed back.
        if (amount.length >= MIN_ECHO_PROBE) assertThat(body.toString()).doesNotContain(amount)

        // Nothing persisted, nothing published.
        assertThat(count("select count(*) from swift_messages where idempotency_key = '$key'")).isZero()
        assertThat(count("select count(*) from swift_messages")).isEqualTo(messagesBefore)
        assertThat(count("select count(*) from swift_outbox")).isEqualTo(outboxBefore)

        // The key is not consumed: a valid retry under the SAME key creates the message, with the
        // retry's own amount — not a replay of anything the refused request left behind.
        Given {
            contentType("application/json")
            body(body(key, "150000", "EUR"))
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(201)
        }
        assertThat(
            count("select count(*) from swift_messages where idempotency_key = '$key' and amount_minor_units = 150000"),
        ).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0} {1} -> 201, stored as {2} {3}")
    @CsvSource(
        "150000, EUR, 150000, EUR",
        "150000, eur, 150000, EUR",
        "150000.00, EUR, 150000, EUR",
        "1.5E+5, EUR, 150000, EUR",
        "1000, JPY, 1000, JPY",
        "1500, KWD, 1500, KWD",
        "1500, BHD, 1500, BHD",
        "1500, CZK, 1500, CZK",
    )
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `valid currencies with any minor unit pass`(amount: String, currency: String, minor: Long, stored: String) {
        val key = "swift-money-ok-${UUID.randomUUID()}"
        val response: Map<String, Any?> = Given {
            contentType("application/json")
            body(body(key, amount, currency))
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        // The response keeps its published shape: currency + amountMinorUnits, no Money object.
        assertThat(response["currency"]).isEqualTo(stored)
        assertThat((response["amountMinorUnits"] as Number).toLong()).isEqualTo(minor)
        assertThat(response).doesNotContainKey("amount")
        assertThat(
            count(
                "select count(*) from swift_messages where idempotency_key = '$key' " +
                    "and amount_minor_units = $minor and currency = '$stored'",
            ),
        ).isEqualTo(1)
    }

    private fun expectedProblem(code: String, field: String): Map<String, Any?> {
        val (type, title, message) = when (code) {
            "AMOUNT_SCALE_EXCEEDED" -> Triple(
                "urn:openbank:error:amount-scale-exceeded",
                "The amount has more decimal places than its currency allows",
                "Amount in minor units must be a whole number",
            )
            "CURRENCY_UNSUPPORTED" -> Triple(
                "urn:openbank:error:currency-unsupported",
                "The currency is not supported",
                "Currency must be an ISO 4217 code with a minor unit",
            )
            else -> Triple(
                "urn:openbank:error:validation-error",
                "The request is not valid",
                "Amount is out of the supported range",
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

    private fun count(sql: String): Long = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun body(key: String, amount: String, currency: String) =
        """
        {
          "idempotencyKey": "$key",
          "messageType": "MT103",
          "senderBic": "OPBKCZPP",
          "receiverBic": "DEUTDEFF",
          "transactionReference": "${UUID.randomUUID().toString().take(REF_LEN)}",
          "relatedReference": null,
          "valueDate": "20260120",
          "currency": "$currency",
          "amountMinorUnits": $amount,
          "orderingCustomerAccount": "DE89370400440532013000",
          "orderingCustomerAccountId": null,
          "orderingCustomerName": "Alice",
          "beneficiaryAccount": "GB33BUKB20201555555555",
          "beneficiaryName": "Bob",
          "remittanceInfo": "money boundary",
          "chargeCode": "SHA",
          "priority": "NORMAL"
        }
        """.trimIndent()
}
