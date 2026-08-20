// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The two party-service 500s the two-pass fuzz lane found once it could get past authentication
 * (#5913). Neither was visible to the auth-on pass — a 403 fires before the handler runs — and
 * neither is visible to any test that mocks the repository or hand-builds the request DTO.
 *
 * 1. `PUT /{id}/payees` answered 500 on EVERY call: `SQLGrammarException: relation
 *    "party_payees_seq" does not exist`. `V18__party_payees.sql` created the pooled id sequence as
 *    the QUOTED `"party_payees_SEQ"`, and a quoted identifier keeps its case in Postgres while the
 *    unquoted name Hibernate emits folds to lowercase — so the two never meet. `V6` fixed exactly
 *    this for the service's other three sequences and `V18` reintroduced it.
 *
 * 2. `POST /directory/lookup` answered 500 for a null INSIDE `phoneHashes`: Kotlin's element
 *    non-nullability is erased past Jackson, so the null reached the handler and the first
 *    dereference threw `Parameter specified as non-null is null`. A null at the TOP level of the body, and a null in
 *    a scalar field, are already 400 — the fleet-wide guards from #3038 cover both, verified by
 *    running these cases against the unfixed build. A null INSIDE a collection is not: the element
 *    type is erased past Jackson, so it reached the handler and threw.
 *
 * Both assert the STATUS. "It did not crash" is the oracle that missed these: each returned a
 * perfectly well-formed `INTERNAL_ERROR` body.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class PartyPayeeAndDirectoryIT {

    private val partyId = UUID.randomUUID()

    @Test
    @TestSecurity(user = "payee-it", roles = ["ROLE_OPERATOR"])
    fun `saving a payee allocates an id instead of answering 500`() {
        Given {
            contentType("application/json")
            body("""{"name":"Alice","iban":"CZ6508000000192000145399"}""")
        } When {
            put("/api/v1/parties/$partyId/payees")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "directory-it", roles = ["ROLE_API"])
    fun `a null element inside phoneHashes is a 400, not a 500`() {
        Given {
            contentType("application/json")
            body("""{"phoneHashes":["abc",null]}""")
        } When {
            post("/api/v1/parties/directory/lookup")
        } Then {
            statusCode(400)
        }
    }
}
