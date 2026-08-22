// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * `POST /api/v1/journals` answered 500 for a null ELEMENT in the `lines` array — found by the
 * two-pass fuzz lane once it could get past authentication (#5913). Kotlin's element
 * non-nullability is a compile-time property that is erased past Jackson, so the null survived
 * deserialization and `request.lines.map { it.toCommand() }` threw NPE.
 *
 * The neighbouring cases are already correct and are NOT asserted here: a null body and a null
 * scalar field both answer 400 today, via the fleet-wide guards from #3038. Asserting those would
 * be a test that is green before and after this change.
 *
 * The assertion is the STATUS. This endpoint answered 500 with a well-formed `INTERNAL_ERROR`
 * body, so "it did not crash" is exactly the oracle that missed it.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class JournalMalformedLinesIT {

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a null element in the lines array is a 400, not a 500`() {
        val today = LocalDate.now().toString()
        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "${UUID.randomUUID()}",
              "entryDate": "$today",
              "valueDate": "$today",
              "description": "null line",
              "createdBy": "00000000-0000-0000-0000-000000000099",
              "lines": [null]
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(400)
        }
    }
}
