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
 * #5913. `POST /api/v1/journals` answered 500 for a body carrying a null element in `lines`:
 *
 *   NullPointerException: Cannot invoke "PostJournalLineRequest.toCommand()" because "it" is null
 *
 * `lines` was declared `List<PostJournalLineRequest>` — a non-null element type. Kotlin's
 * null-safety is compile-time only and does not survive into the collection at runtime, so Jackson
 * puts a null straight into the list and the `map { it.toCommand() }` throws. The declared type was
 * a promise the runtime never kept, exactly like a non-null `@QueryParam` (CLAUDE.md): the type only
 * decides WHERE the failure lands, never whether one happens.
 *
 * ADR-0080: no input, however malformed, may produce a 5xx. A caller sending a null line is a client
 * error, and reporting it as 5xx spends a money-path service's error budget on someone else's typo.
 *
 * The assertion is the STATUS CODE, not that no exception escaped — the endpoint already returned a
 * well-formed `INTERNAL_ERROR` body while doing the wrong thing, so "it responded" is precisely the
 * oracle that missed this.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class PostJournalMalformedBodyIT {

    private val operatorId = UUID.randomUUID()
    private val today: String = LocalDate.now().toString()

    private fun requestBody(lines: String) = """
        {
          "idempotencyKey": "${UUID.randomUUID()}",
          "transactionId": "${UUID.randomUUID()}",
          "entryDate": "$today",
          "valueDate": "$today",
          "description": "malformed-body probe",
          "createdBy": "$operatorId",
          "lines": $lines
        }
    """.trimIndent()

    private val validLine = """
        {
          "glAccountId": "a0000000-0000-0000-0000-000000000001",
          "side": "DEBIT",
          "amount": "1000.00",
          "currencyCode": "CZK",
          "baseAmount": "1000.00",
          "baseCurrencyCode": "CZK"
        }
    """.trimIndent()

    @Test
    @TestSecurity(user = "malformed-it", roles = ["ROLE_OPERATOR"])
    fun `a null element in lines is a client error, not a server error`() {
        Given {
            contentType("application/json")
            body(requestBody("[null, $validLine]"))
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(400)
        }
    }

    /**
     * The all-null case is separate because it exercises a different index: a fix that only guarded
     * the first element would still throw on `[valid, null]`, and a single test cannot tell those
     * apart.
     */
    @Test
    @TestSecurity(user = "malformed-it", roles = ["ROLE_OPERATOR"])
    fun `a null element after a valid one is also a client error`() {
        Given {
            contentType("application/json")
            body(requestBody("[$validLine, null]"))
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(400)
        }
    }
}
