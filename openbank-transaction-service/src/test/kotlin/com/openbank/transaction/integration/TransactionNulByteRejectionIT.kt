// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The two `openbank-transaction-service` operations from #5913 that answered **500** for a NUL
 * character (U+0000) in a string, driven over real HTTP against a real Postgres.
 *
 * Both failed identically at the driver — `invalid byte sequence for encoding "UTF8": 0x00`
 * (SQLState 22021), surfaced as `org.hibernate.exception.DataException` and rendered by
 * `GenericExceptionMapper` as a well-formed `INTERNAL_ERROR` body. **That body is why an oracle of
 * "it did not crash" or "the response parsed" passes against the defect**, and why every assertion
 * here is on the status code.
 *
 * They are two different carriers, which is the reason both are here rather than one standing in
 * for the other:
 *
 * - `GET /api/v1/transactions/search?referenceNumber=…` — the NUL arrives **percent-encoded in the
 *   query string**. The request has no entity at all, so a body-side guard never runs.
 * - `POST /api/v1/transactions/{id}/reverse` — the NUL arrives in the **JSON body**, escaped, in
 *   `idempotencyKey`.
 *
 * The fix is a single shared boundary in `openbank-libs-runtime`
 * (`com.openbank.libs.api.error.NulByteGuards`), so this IT is also the check that it is actually
 * *registered* in a running service: a unit test over the guard classes cannot tell a discovered
 * CDI bean and JAX-RS provider from an undiscovered one.
 *
 * The `search` rejection is paired with a **control** sending the same request without the NUL,
 * which must NOT answer 400 — without it, a guard that rejected every request would pass this class
 * while taking the service down. `reverse` has no such control, for a reason recorded at the bottom
 * of this file.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
class TransactionNulByteRejectionIT {

    /** U+0000 written as a Kotlin escape — no control character enters this source file. */
    private val nul = "\u0000"

    /** The JSON escape for U+0000 as it travels on the wire: six ASCII characters, legal JSON. */
    private val escapedNul = "\\u0000"

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `search rejects a NUL in the referenceNumber query parameter with 400`() {
        Given {
            queryParam("referenceNumber", "+ab${nul}cd")
            queryParam("type", "DEBIT")
        } When {
            get("/api/v1/transactions/search")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `search accepts the same referenceNumber without the NUL`() {
        Given {
            queryParam("referenceNumber", "+abcd")
            queryParam("type", "DEBIT")
        } When {
            get("/api/v1/transactions/search")
        } Then {
            statusCode(not(400))
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `reverse rejects a NUL in the idempotencyKey body field with 400`() {
        Given {
            contentType("application/json")
            body("""{"idempotencyKey":"ab${escapedNul}cd","reason":"customer dispute"}""")
        } When {
            post("/api/v1/transactions/${UUID.randomUUID()}/reverse")
        } Then {
            statusCode(400)
        }
    }

    // No status-only control exists for `reverse`, and that is a measured fact rather than an
    // omission: the same body WITHOUT the NUL also answers 400, because a random transactionId is
    // not found and the use case reports that as an IllegalArgumentException. A `not(400)` control
    // was written here first and went red for exactly that reason.
    //
    // What discriminates on this endpoint is the falsification, which was run: with
    // `NulByte.contains` forced to false, both rejection tests above answer **500** — the status
    // and the cause the issue reported. `search accepts the same referenceNumber without the NUL`
    // carries the other half of the property, that the guard does not simply reject everything.
}
