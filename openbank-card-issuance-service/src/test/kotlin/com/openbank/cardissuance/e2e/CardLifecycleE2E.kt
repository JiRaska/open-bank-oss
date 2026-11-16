// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.e2e

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The card lifecycle journey over the real HTTP surface: issue → read (every read shape) →
 * activate → suspend → resume → block → cancel. Ordered because the lifecycle is a state machine
 * — each step is only reachable from the previous one — and `@TestSecurity` fixes one identity
 * per method.
 *
 * The integration tests in this module prove one property each (trace contract on the issue,
 * outbox atomicity, party list limits); this proves the operator-visible route sequence serves
 * the whole lifecycle over HTTP, so a wiring regression that deregisters a route (#3371), drops
 * the `X-Operator-Id` requirement, or answers the wrong status for a lifecycle step fails here
 * even when every property-level assertion stays green.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "synthetic-staff", roles = ["ROLE_OPERATOR", "ROLE_VIEWER"])
@OidcSecurity(
    claims = [
        // The verified-human shape ServiceAccountIdentity.isHumanStaff requires: an interactive
        // client, a real subject, and a username equal to the principal name. Without these the
        // two named-caller reads (GET /{id}, GET /party/{partyId}) answer 403 even for staff —
        // CardReadCallerGuardTest pins that contract.
        Claim(key = "azp", value = "openbank-admin-ui"),
        Claim(key = "sub", value = "00000000-0000-0000-0000-000000000099"),
        Claim(key = "preferred_username", value = "synthetic-staff"),
    ],
)
class CardLifecycleE2E {

    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()

    @Test
    @Order(1)
    fun `1 - an operator issues a card and reads it back in every read shape`() {
        cardId = given()
            .contentType("application/json")
            .header("Idempotency-Key", "e2e-${UUID.randomUUID()}")
            .body(issueBody())
            .post("/api/v1/cards")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("status", equalTo("ACTIVE"))
            .body("partyId", equalTo(party.toString()))
            .extract()
            .path<String>("id")

        given().header("Content-Type", "application/json").get("/api/v1/cards/{id}", cardId).then()
            .statusCode(200)
            .body("id", equalTo(cardId))
            .body("status", equalTo("ACTIVE"))
            .body("productCode", equalTo(PRODUCT))

        given().header("Content-Type", "application/json").get("/api/v1/cards/party/{partyId}", party).then()
            .statusCode(200)
            .body("id", hasItem(cardId))

        given().header("Content-Type", "application/json").get("/api/v1/cards/account/{accountId}", account).then()
            .statusCode(200)
            .body("id", hasItem(cardId))
    }

    @Test
    @Order(2)
    fun `2 - a virtual card is live on issue and suspend and resume park it and bring it back`() {
        given()
            .contentType("application/json")
            .header("X-Operator-Id", "00000000-0000-0000-0000-000000000099")
            .post("/api/v1/cards/{id}/suspend", cardId)
            .then()
            .statusCode(200)
            .body("status", equalTo("SUSPENDED"))

        given()
            .contentType("application/json")
            .header("X-Operator-Id", "00000000-0000-0000-0000-000000000099")
            .post("/api/v1/cards/{id}/resume", cardId)
            .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"))
    }

    @Test
    @Order(3)
    fun `3 - a block without the operator header is refused and with it the card blocks`() {
        given()
            .contentType("application/json")
            .body("""{"reason":"reported lost by the customer"}""")
            .post("/api/v1/cards/{id}/block", cardId)
            .then()
            .statusCode(400)

        given()
            .contentType("application/json")
            .header("X-Operator-Id", "00000000-0000-0000-0000-000000000099")
            .body("""{"reason":"reported lost by the customer"}""")
            .post("/api/v1/cards/{id}/block", cardId)
            .then()
            .statusCode(200)
            .body("status", equalTo("BLOCKED"))
            .body("blockedReason", equalTo("reported lost by the customer"))
    }

    @Test
    @Order(4)
    fun `4 - cancel closes the card for good from the blocked state`() {
        given()
            .contentType("application/json")
            .header("X-Operator-Id", "00000000-0000-0000-0000-000000000099")
            .body("""{"reason":"customer closed the account"}""")
            .post("/api/v1/cards/{id}/cancel", cardId)
            .then()
            .statusCode(200)
            .body("status", equalTo("CANCELLED"))

        given().header("Content-Type", "application/json").get("/api/v1/cards/{id}", cardId).then()
            .statusCode(200)
            .body("status", equalTo("CANCELLED"))
    }

    private fun issueBody() = """
        {
          "partyId": "$party",
          "accountId": "$account",
          "productCode": "$PRODUCT",
          "cardType": "VIRTUAL",
          "network": "VISA",
          "cardholderName": "E2E Cardholder",
          "embossedName": "E2E CARDHOLDER",
          "currency": "CZK"
        }
    """.trimIndent()

    private companion object {
        const val PRODUCT = "E2E-VIRTUAL"
        lateinit var cardId: String
    }
}
