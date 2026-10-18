// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.e2e

import com.openbank.delegation.integration.SpendReservationOutboxWriteIT
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The grantee side and the closing sides of the ADR-0232 delegation lifecycle over the real HTTP
 * surface against real Postgres + Valkey: accept/decline, per-amount coverage checks, renounce,
 * grantor revocation with outbox evidence over plain JDBC, the bank operator suspend/reinstate
 * path, and party-scoped reads. Companion of [DelegationOfferCeremonyE2E].
 */
@QuarkusTest
@TestProfile(E2EProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class, restrictToAnnotatedClass = true)
class DelegationGranteeLifecycleE2E : DelegationE2EBase() {

    @Test
    @Order(1)
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = EDGE_SUB),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE_PRINCIPAL),
        ],
    )
    fun `accept binds the grantee and an ACCEPT-purpose SCA session and activates once`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val offered = offerAndGetId(grantor, grantee, UUID.randomUUID(), readOnlyCapabilities())

        // #3104: the SCA session is a required query parameter -> 400.
        assertThat(acceptCall(offered, grantee, grantee, null).statusCode()).isEqualTo(400)

        // A different party is not the grantee -> 403, and its challenge is untouched.
        val wrongGranteeSca = UUID.randomUUID()
        scaStub.seed(wrongGranteeSca, grantee, SCA_PURPOSE_ACCEPT)
        assertThat(acceptCall(offered, grantee, UUID.randomUUID(), wrongGranteeSca).statusCode()).isEqualTo(403)
        assertThat(scaStub.isSpent(wrongGranteeSca)).isFalse()

        // A GRANT-purpose challenge cannot activate the grant.
        val wrongPurposeSca = UUID.randomUUID()
        scaStub.seed(wrongPurposeSca, grantee, SCA_PURPOSE_GRANT)
        assertThat(acceptCall(offered, grantee, grantee, wrongPurposeSca).statusCode()).isEqualTo(400)

        val acceptSca = UUID.randomUUID()
        scaStub.seed(acceptSca, grantee, SCA_PURPOSE_ACCEPT)
        val accepted = acceptCall(offered, grantee, grantee, acceptSca)
        assertThat(accepted.statusCode()).isEqualTo(200)
        assertThat(accepted.jsonPath().getString("status")).isEqualTo("ACTIVE")

        // Only an OFFERED grant can be accepted: a second ceremony on an ACTIVE grant is 422.
        val secondSca = UUID.randomUUID()
        scaStub.seed(secondSca, grantee, SCA_PURPOSE_ACCEPT)
        assertThat(acceptCall(offered, grantee, grantee, secondSca).statusCode()).isEqualTo(422)
    }

    @Test
    @Order(2)
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = EDGE_SUB),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE_PRINCIPAL),
        ],
    )
    fun `decline closes an OFFERED grant terminally`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val offered = offerAndGetId(grantor, grantee, UUID.randomUUID(), readOnlyCapabilities())

        val declined = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantee.toString())
            .queryParam("granteePartyId", grantee.toString())
            .post("/api/v1/delegations/$offered/decline")
            .then()
            .statusCode(200)
            .extract()
        assertThat(declined.jsonPath().getString("status")).isEqualTo("DECLINED")
        assertThat(declined.jsonPath().getString("closedAt")).isNotBlank()

        // Terminal: neither a second decline nor an accept can move it (422 via the aggregate).
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantee.toString())
            .queryParam("granteePartyId", grantee.toString())
            .post("/api/v1/delegations/$offered/decline")
            .then()
            .statusCode(422)

        val lateSca = UUID.randomUUID()
        scaStub.seed(lateSca, grantee, SCA_PURPOSE_ACCEPT)
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantee.toString())
            .queryParam("granteePartyId", grantee.toString())
            .queryParam("scaSessionId", lateSca.toString())
            .post("/api/v1/delegations/$offered/accept")
            .then()
            .statusCode(422)
    }

    @Test
    @Order(3)
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = EDGE_SUB),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE_PRINCIPAL),
        ],
    )
    fun `check answers per-amount coverage and fails closed on the priced-grant edge cases`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val resourceId = UUID.randomUUID()
        val priced = offerAndGetId(
            grantor,
            grantee,
            resourceId,
            EXECUTION_CAPABILITIES,
            limitClause = EXECUTION_LIMITS_CLAUSE + LIMIT_1000_CZK,
        )
        activate(priced, grantee)

        // Inside the ceiling -> covered.
        assertThat(check(grantee, resourceId, "ACCOUNT_INITIATE_PAYMENT", 500, "CZK")).isTrue()
        // Over the ceiling -> denied with the machine code.
        val over = checkDenied(grantee, resourceId, "ACCOUNT_INITIATE_PAYMENT", 1500, "CZK")
        assertThat(over.jsonPath().getString("code")).isEqualTo("DELEGATION_NOT_COVERED")
        // Unpriced question against a priced grant is a DENIAL, not coverage (#3800).
        val unpriced = checkDenied(grantee, resourceId, "ACCOUNT_INITIATE_PAYMENT", null, null)
        assertThat(unpriced.jsonPath().getBoolean("granted")).isFalse()
        // A currency the ceiling is not denominated in is a denial, never a crash (#3615 class).
        assertThat(check(grantee, resourceId, "ACCOUNT_INITIATE_PAYMENT", 500, "EUR")).isFalse()
        // A capability not on the grant is denied.
        assertThat(check(grantee, resourceId, "ACCOUNT_VIEW_DETAILS", 100, "CZK")).isFalse()
        // Another grantee has no coverage on this resource.
        assertThat(check(UUID.randomUUID(), resourceId, "ACCOUNT_INITIATE_PAYMENT", 100, "CZK")).isFalse()
    }

    @Test
    @Order(4)
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = EDGE_SUB),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE_PRINCIPAL),
        ],
    )
    fun `renounce hands an ACTIVE grant back and is terminal`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val active = offerAndGetId(grantor, grantee, UUID.randomUUID(), readOnlyCapabilities())
        activate(active, grantee)

        val renounced = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantee.toString())
            .queryParam("granteePartyId", grantee.toString())
            .post("/api/v1/delegations/$active/renounce")
            .then()
            .statusCode(200)
            .extract()
        assertThat(renounced.jsonPath().getString("status")).isEqualTo("RENOUNCED")

        // Terminal for BOTH sides: the grantee cannot renounce twice, the grantor cannot revoke.
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantee.toString())
            .queryParam("granteePartyId", grantee.toString())
            .post("/api/v1/delegations/$active/renounce")
            .then()
            .statusCode(422)
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantor.toString())
            .body("""{"reason":"no longer needed"}""")
            .delete("/api/v1/delegations/$active")
            .then()
            .statusCode(422)
    }

    @Test
    @Order(5)
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = EDGE_SUB),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE_PRINCIPAL),
        ],
    )
    fun `grantor revocation closes the grant and commits DelegationRevoked to the outbox`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val active = offerAndGetId(grantor, grantee, UUID.randomUUID(), readOnlyCapabilities())
        activate(active, grantee)

        val revoked = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantor.toString())
            .body("""{"reason":"suspected misuse"}""")
            .delete("/api/v1/delegations/$active")
            .then()
            .statusCode(200)
            .extract()
        assertThat(revoked.jsonPath().getString("status")).isEqualTo("REVOKED")
        assertThat(revoked.jsonPath().getString("closedReason")).isEqualTo("suspected misuse")

        // The event and the status change commit atomically: exactly one DelegationRevoked row.
        assertThat(outboxEventsFor(active, "DelegationRevoked")).isEqualTo(1)

        // Terminal: a second revocation is 422.
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantor.toString())
            .body("""{"reason":"again"}""")
            .delete("/api/v1/delegations/$active")
            .then()
            .statusCode(422)
    }

    @Test
    @Order(6)
    @TestSecurity(user = OPERATOR_PRINCIPAL, roles = ["ROLE_OPERATOR", "ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "b7d3e1f4-2a6c-4e8b-9d5f-1c3a7e9b2d48"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = OPERATOR_PRINCIPAL),
        ],
    )
    fun `bank suspend and reinstate gate on the operator path and close a suspended grant`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()

        // Bank-side offer: no customer headers, the operator context derives the actor.
        val sca = UUID.randomUUID()
        scaStub.seed(sca, grantor, SCA_PURPOSE_GRANT)
        val offered = offerSpec(grantor, grantee, UUID.randomUUID(), sca, customer = null)
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
            .extract()
        val id = UUID.fromString(offered.jsonPath().getString("id"))
        val acceptSca = UUID.randomUUID()
        scaStub.seed(acceptSca, grantee, SCA_PURPOSE_ACCEPT)
        RestAssured.given()
            .contentType(ContentType.JSON)
            .queryParam("granteePartyId", grantee.toString())
            .queryParam("scaSessionId", acceptSca.toString())
            .post("/api/v1/delegations/$id/accept")
            .then()
            .statusCode(200)

        // Method-level narrowing: suspend is ROLE_OPERATOR/ROLE_ADMIN only.
        val suspended = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""{"reason":"fraud signal"}""")
            .post("/api/v1/delegations/$id/suspend")
            .then()
            .statusCode(200)
            .extract()
        assertThat(suspended.jsonPath().getString("status")).isEqualTo("SUSPENDED")

        // A suspended grant cannot be suspended again.
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""{"reason":"again"}""")
            .post("/api/v1/delegations/$id/suspend")
            .then()
            .statusCode(422)

        // Reinstatement brings it back.
        val reinstated = RestAssured.given()
            .contentType(ContentType.JSON)
            .post("/api/v1/delegations/$id/reinstate")
            .then()
            .statusCode(200)
            .extract()
        assertThat(reinstated.jsonPath().getString("status")).isEqualTo("ACTIVE")

        // And the bank can close a suspended grant via the revokedBy path.
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""{"reason":"confirmed fraud"}""")
            .post("/api/v1/delegations/$id/suspend")
            .then()
            .statusCode(200)
        val revoked = RestAssured.given()
            .contentType(ContentType.JSON)
            .queryParam("revokedBy", grantor.toString())
            .body("""{"reason":"confirmed fraud"}""")
            .delete("/api/v1/delegations/$id")
            .then()
            .statusCode(200)
            .extract()
        assertThat(revoked.jsonPath().getString("status")).isEqualTo("REVOKED")
        assertThat(outboxEventsFor(id, "DelegationRevoked")).isEqualTo(1)
    }

    @Test
    @Order(7)
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = EDGE_SUB),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE_PRINCIPAL),
        ],
    )
    fun `reads are party-scoped and anonymous or viewer callers get nothing`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val id = offerAndGetId(grantor, grantee, UUID.randomUUID(), readOnlyCapabilities())

        // A stranger's customer session reads nothing: the id space does not leak (404, not 403).
        val stranger = UUID.randomUUID()
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", stranger.toString())
            .get("/api/v1/delegations/$id")
            .then()
            .statusCode(404)

        // Listing another party's grants is a caller mismatch -> 403.
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", stranger.toString())
            .get("/api/v1/delegations/grantor/$grantor")
            .then()
            .statusCode(403)

        // The grantee's own list contains the grant.
        val listed = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantee.toString())
            .get("/api/v1/delegations/grantee/$grantee")
            .then()
            .statusCode(200)
            .extract()
        assertThat(listed.jsonPath().getList<String>("id")).contains(id.toString())

        // Unknown id -> 404.
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantor.toString())
            .get("/api/v1/delegations/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(8)
    fun `anonymous callers are refused before any handler runs`() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .get("/api/v1/delegations/${UUID.randomUUID()}")
            .then()
            .statusCode(401)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "analyst-7", roles = ["ROLE_VIEWER"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "c8e4f2a5-3b7d-4f9a-8e1c-2d4b6a9f3e51"),
            Claim(key = "preferred_username", value = "analyst-7"),
        ],
    )
    fun `a viewer role is below the class-level role floor`() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .get("/api/v1/delegations/${UUID.randomUUID()}")
            .then()
            .statusCode(403)
    }
}
