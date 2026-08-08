// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

/**
 * `POST /api/v1/fraud/score` must answer a **client** error for a malformed body, never 500.
 *
 * Reproduces the exact reproducer schemathesis emitted in the 2026-08-03 `api-fuzz-authenticated`
 * run (30804842325, job 91657718387, `Server error: 1`):
 *
 * ```
 * {"amount": "1250.00", "currency": false, "rail": "SEPA_INSTANT",
 *  "accountId": null, "counterpartyId": null}
 * ```
 *
 * `currency` is declared `String` and receives a JSON boolean. Whatever the deserialiser does with
 * that — coerce to `"false"` and fail a downstream currency lookup, or reject outright — a caller
 * sending a wrongly-typed field is a 4xx, and a money-path service reporting it as 5xx inflates its
 * error budget and keeps the fuzz lane red.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
class FraudScoreMalformedBodyIT {

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `a wrongly-typed currency field is a client error, not a server error`() {
        Given {
            contentType("application/json")
            body(
                """{"amount": "1250.00", "currency": false, "rail": "SEPA_INSTANT",
                   "accountId": null, "counterpartyId": null}""",
            )
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(400)
        }
    }

    /**
     * The same defect reachable without a type error: a well-typed but over-long `currency` also
     * overflowed `fraud_scores.currency varchar(3)`. Guards against a fix that only special-cases
     * the boolean coercion.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `an over-long currency is a client error, not a server error`() {
        Given {
            contentType("application/json")
            body("""{"amount": "1250.00", "currency": "CZKK", "rail": "SEPA_INSTANT"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(400)
        }
    }

    /** A blank `rail` must not reach the rule engine as a valid intent. */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `a blank rail is a client error`() {
        Given {
            contentType("application/json")
            body("""{"amount": "1250.00", "currency": "CZK", "rail": ""}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(400)
        }
    }

    /** Control: the known-good body from `FraudApiIT` must still score. Proves the guard is narrow. */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `control - a well-formed request still scores`() {
        Given {
            contentType("application/json")
            body("""{"amount": "1250.00", "currency": "CZK", "rail": "SEPA_INSTANT"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(200)
        }
    }
}
