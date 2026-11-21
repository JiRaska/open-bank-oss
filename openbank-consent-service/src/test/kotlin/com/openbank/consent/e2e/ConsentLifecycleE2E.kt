// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.e2e

import com.openbank.consent.application.port.out.ScaChallengeSnapshot
import com.openbank.consent.infrastructure.client.ResilientScaChallengeClient
import com.openbank.consent.it.ConsentPostgresRedisTestResource
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The consent customer journey over the real HTTP surface: grant → read (every read shape) →
 * SCA activation → coverage checks → revoke → checks go dark. Ordered because the lifecycle is a
 * state machine — activation is only reachable from PENDING_SCA, revocation from ACTIVE — and
 * `@TestSecurity` fixes one identity per method.
 *
 * The integration tests in this module prove one transition each (idempotency fingerprint,
 * revocation outbox, trace contract); this proves the customer-visible route sequence serves the
 * whole lifecycle over HTTP, so a wiring regression that deregisters a route or answers the wrong
 * status for a lifecycle step fails here even when every table-level assertion stays green.
 *
 * Activation's SCA proof comes over the `ScaChallengeClient` port (sca-service is not in this
 * module's test universe): the mock answers a COMPLETED CONSENT_GRANT challenge for the journey's
 * party, the same shape ConsentServiceTest mocks at the unit level. Everything else — Postgres,
 * Redis idempotency, the HTTP routes — is real.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
class ConsentLifecycleE2E {

    private val party = UUID.randomUUID()
    private val scaSessionId = UUID.randomUUID()

    @BeforeEach
    fun scaChallengeCompletes() {
        val client = mockk<ResilientScaChallengeClient>()
        coEvery { client.getChallenge(any()) } answers {
            ScaChallengeSnapshot(
                id = firstArg(),
                partyId = party,
                purpose = "CONSENT_GRANT",
                status = "COMPLETED",
            )
        }
        QuarkusMock.installMockForType(client, ResilientScaChallengeClient::class.java)
    }

    @Test
    @Order(1)
    fun `1 - a TPP grants a consent and reads it back in every read shape`() {
        createdId = given()
            .contentType("application/json")
            .header("X-Request-ID", "e2e-${UUID.randomUUID()}")
            .body(createBody())
            .post("/api/v1/consents")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("status", equalTo("PENDING_SCA"))
            .body("partyId", equalTo(party.toString()))
            .extract()
            .path<String>("id")

        given().header("Content-Type", "application/json").get("/api/v1/consents/{id}", createdId).then()
            .statusCode(200)
            .body("id", equalTo(createdId))
            .body("status", equalTo("PENDING_SCA"))
            .body("granteeId", equalTo(GRANTEE))

        given().header("Content-Type", "application/json").get("/api/v1/consents/party/{partyId}", party).then()
            .statusCode(200)
            .body("id", hasItem(createdId))

        given().header("Content-Type", "application/json").get("/api/v1/consents/grantee/{granteeId}", GRANTEE).then()
            .statusCode(200)
            .body("id", hasItem(createdId))
    }

    @Test
    @Order(2)
    fun `2 - SCA completion activates the consent and the coverage checks answer granted`() {
        given()
            .contentType("application/json")
            .queryParam("scaSessionId", scaSessionId)
            .post("/api/v1/consents/{id}/activate", createdId)
            .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"))

        given().header("Content-Type", "application/json")
            .queryParam("scope", "ACCOUNTS_READ")
            .get("/api/v1/consents/party/{partyId}/grantee/{granteeId}/active", party, GRANTEE)
            .then()
            .statusCode(200)
            .body("granted", equalTo(true))
    }

    @Test
    @Order(3)
    fun `3 - a resource server validates coverage - granted scope passes, an ungranted scope is refused`() {
        given()
            .contentType("application/json")
            .body("""{"granteeId":"$GRANTEE","requiredScope":"ACCOUNTS_READ","accountIban":"$IBAN"}""")
            .post("/api/v1/consents/{id}/validate", createdId)
            .then()
            .statusCode(200)
            .body("valid", equalTo(true))

        given()
            .contentType("application/json")
            .body("""{"granteeId":"$GRANTEE","requiredScope":"PAYMENTS_INITIATE","accountIban":"$IBAN"}""")
            .post("/api/v1/consents/{id}/validate", createdId)
            .then()
            .statusCode(200)
            .body("valid", equalTo(false))
    }

    @Test
    @Order(4)
    fun `4 - revocation darkens every coverage check`() {
        given()
            .contentType("application/json")
            .queryParam("partyId", party)
            .body("""{"reason":"customer withdrew consent in the TPP app"}""")
            .delete("/api/v1/consents/{id}", createdId)
            .then()
            .statusCode(200)
            .body("status", equalTo("REVOKED"))

        given().header("Content-Type", "application/json")
            .queryParam("scope", "ACCOUNTS_READ")
            .get("/api/v1/consents/party/{partyId}/grantee/{granteeId}/active", party, GRANTEE)
            .then()
            .statusCode(200)
            .body("granted", equalTo(false))

        given()
            .contentType("application/json")
            .body("""{"granteeId":"$GRANTEE","requiredScope":"ACCOUNTS_READ","accountIban":"$IBAN"}""")
            .post("/api/v1/consents/{id}/validate", createdId)
            .then()
            .statusCode(200)
            .body("valid", equalTo(false))
    }

    private fun createBody() = """
        {
          "partyId": "$party",
          "granteeId": "$GRANTEE",
          "granteeType": "TPP",
          "granteeName": "E2E TPP",
          "scopes": ["ACCOUNTS_READ"],
          "accountIbans": ["$IBAN"],
          "validTo": "${OffsetDateTime.now().plusDays(90)}"
        }
    """.trimIndent()

    private companion object {
        const val GRANTEE = "tpp-e2e-lifecycle"
        const val IBAN = "CZ6508000000192000145399"
        lateinit var createdId: String
    }
}
