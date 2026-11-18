// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.e2e

import com.openbank.delegation.application.port.out.GrantorAuthority
import com.openbank.delegation.application.port.out.GrantorAuthorityVerdict
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.infrastructure.client.ResilientPartyEligibilityClient
import com.openbank.delegation.infrastructure.client.ResilientScaChallengeClient
import com.openbank.delegation.infrastructure.client.RestGrantorAuthorityClient
import com.openbank.delegation.infrastructure.client.RestResourceOwnershipClient
import com.openbank.delegation.it.PostgresTestResource
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.ws.rs.NotFoundException
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The delegation grant journey over the real HTTP surface: offer (idempotent) → read (detail,
 * by-grantor, by-grantee) → accept (SCA-bound, single-use) → suspend → reinstate → revoke.
 * Ordered because the lifecycle is a state machine — each step is only reachable from the
 * previous one.
 *
 * The integration tests in this module prove one property each (revision ordering, recertification
 * replay, spend-reservation concurrency); this proves the operator-visible route sequence serves
 * the whole lifecycle over HTTP, so a wiring regression that deregisters a route (#3371), drops
 * the `X-Request-ID` idempotency envelope, or answers the wrong status for a lifecycle step
 * fails here even when every property-level assertion stays green.
 *
 * The three outbound ceremony dependencies stand in for their services (the module has no
 * WireMock convention; every other integration test mocks the client interfaces too):
 * pid-service eligibility, party-service authority, the resource-ownership oracle and the
 * sca-service compare-and-consume gate. The SCA fake keeps real single-use semantics — a spent
 * challenge refuses a second consume — because RTS Art. 5 single-use is the property the
 * accept-replay assertion pins.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(DelegationLifecycleE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DelegationLifecycleE2E.NoBackgroundWorkProfile::class)
@TestSecurity(user = "synthetic-operator", roles = ["ROLE_OPERATOR"])
@OidcSecurity(
    claims = [
        Claim(key = "azp", value = "openbank-admin-ui"),
        Claim(key = "sub", value = "00000000-0000-0000-0000-000000000099"),
        Claim(key = "preferred_username", value = "synthetic-operator"),
    ],
)
class DelegationLifecycleE2E {

    private val grantor = UUID.randomUUID()
    private val grantee = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val requestId = "e2e-${UUID.randomUUID()}"
    private val offerScaSession = UUID.randomUUID()
    private val acceptScaSession = UUID.randomUUID()

    private val offerBody = """
        {
          "grantorPartyId": "$grantor",
          "granteePartyId": "$grantee",
          "resourceType": "ACCOUNT",
          "resourceId": "$account",
          "capabilities": ["ACCOUNT_VIEW_DETAILS"],
          "approvalPolicy": "SOLO",
          "grantScaSessionId": "$offerScaSession",
          "note": "E2E lifecycle journey"
        }
    """.trimIndent()

    @BeforeAll
    fun installFakes() {
        val sca = mockk<ResilientScaChallengeClient>()
        val challenges = ConcurrentHashMap<UUID, ScaChallengeSnapshot>()
        val consumed = ConcurrentHashMap.newKeySet<UUID>()
        fun snapshot(id: UUID, partyId: UUID, purpose: String) =
            ScaChallengeSnapshot(id = id, partyId = partyId, purpose = purpose, status = "COMPLETED")
        challenges[offerScaSession] = snapshot(offerScaSession, grantor, "DELEGATION_GRANT")
        challenges[acceptScaSession] = snapshot(acceptScaSession, grantee, "DELEGATION_ACCEPT")
        coEvery { sca.getChallenge(any()) } answers {
            challenges[firstArg<UUID>()] ?: throw NotFoundException("no such challenge")
        }
        coEvery { sca.consumeChallenge(any(), any(), any()) } answers {
            val id = firstArg<UUID>()
            val partyId = secondArg<UUID>()
            val challenge = challenges[id] ?: throw NotFoundException("no such challenge")
            check(challenge.partyId == partyId) { "challenge party mismatch" }
            check(consumed.add(id)) { "challenge $id already consumed" }
            challenge
        }
        QuarkusMock.installMockForType(sca, ResilientScaChallengeClient::class.java)

        val eligibility = mockk<ResilientPartyEligibilityClient>()
        coEvery { eligibility.eligibilityOf(any()) } answers {
            PartyEligibility(
                partyId = firstArg(),
                active = true,
                kycLevel = "FULL",
                displayName = "Grantee",
                partyType = "INDIVIDUAL",
            )
        }
        QuarkusMock.installMockForType(eligibility, ResilientPartyEligibilityClient::class.java)

        val authority = mockk<RestGrantorAuthorityClient>()
        coEvery { authority.authorityFor(any(), any()) } answers {
            GrantorAuthority(
                verdict = GrantorAuthorityVerdict.AUTHORIZED,
                displayName = "Grantor",
                partyType = "INDIVIDUAL",
            )
        }
        QuarkusMock.installMockForType(authority, RestGrantorAuthorityClient::class.java)

        val ownership = mockk<RestResourceOwnershipClient>()
        coEvery { ownership.verifyOwnership(any(), any(), any()) } returns OwnershipVerdict.OWNED
        QuarkusMock.installMockForType(ownership, RestResourceOwnershipClient::class.java)
    }

    @Test
    @Order(1)
    fun `1 - an operator offers a grant idempotently and reads it back in every read shape`() {
        grantId = given()
            .contentType("application/json")
            .header("X-Request-ID", requestId)
            .body(offerBody)
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("status", equalTo("OFFERED"))
            .body("grantorPartyId", equalTo(grantor.toString()))
            .body("granteePartyId", equalTo(grantee.toString()))
            .extract()
            .path<String>("id")

        given().header("Content-Type", "application/json").get("/api/v1/delegations/{id}", grantId).then()
            .statusCode(200)
            .body("id", equalTo(grantId))
            .body("status", equalTo("OFFERED"))
            .body("grantorName", equalTo("Grantor"))
            .body("granteeName", equalTo("Grantee"))

        given().header("Content-Type", "application/json")
            .get("/api/v1/delegations/grantor/{partyId}", grantor).then()
            .statusCode(200)
            .body("id", hasItem(grantId))

        given().header("Content-Type", "application/json")
            .get("/api/v1/delegations/grantee/{partyId}", grantee).then()
            .statusCode(200)
            .body("id", hasItem(grantId))
    }

    @Test
    @Order(2)
    fun `2 - replaying the offer returns the stored grant without minting a second one`() {
        given()
            .contentType("application/json")
            .header("X-Request-ID", requestId)
            .body(offerBody)
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
            .header("X-Idempotency-Replayed", equalTo("true"))
            .body("id", equalTo(grantId))

        given().header("Content-Type", "application/json")
            .get("/api/v1/delegations/grantor/{partyId}", grantor).then()
            .statusCode(200)
            .body("id", hasItem(grantId))
            .body("id.size()", equalTo(1))
    }

    @Test
    @Order(3)
    fun `3 - the grantee accepts under a fresh SCA session and the session is single-use`() {
        given()
            .contentType("application/json")
            .header("X-Customer-Party-Id", grantee.toString())
            .queryParam("granteePartyId", grantee.toString())
            .queryParam("scaSessionId", acceptScaSession.toString())
            .post("/api/v1/delegations/{id}/accept", grantId)
            .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"))

        given()
            .contentType("application/json")
            .header("X-Customer-Party-Id", grantee.toString())
            .queryParam("granteePartyId", grantee.toString())
            .queryParam("scaSessionId", acceptScaSession.toString())
            .post("/api/v1/delegations/{id}/accept", grantId)
            .then()
            .statusCode(400)
    }

    @Test
    @Order(4)
    fun `4 - bank suspend and reinstate park the grant and bring it back`() {
        given()
            .contentType("application/json")
            .body("""{"reason":"fraud-AML signal"}""")
            .post("/api/v1/delegations/{id}/suspend", grantId)
            .then()
            .statusCode(200)
            .body("status", equalTo("SUSPENDED"))

        given()
            .contentType("application/json")
            .post("/api/v1/delegations/{id}/reinstate", grantId)
            .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"))
    }

    @Test
    @Order(5)
    fun `5 - bank revoke closes the grant for good from the active state`() {
        given()
            .contentType("application/json")
            .queryParam("revokedBy", grantor.toString())
            .body("""{"reason":"aml escalation"}""")
            .delete("/api/v1/delegations/{id}", grantId)
            .then()
            .statusCode(200)
            .body("status", equalTo("REVOKED"))
            .body("closedReason", equalTo("aml escalation"))

        given().header("Content-Type", "application/json").get("/api/v1/delegations/{id}", grantId).then()
            .statusCode(200)
            .body("status", equalTo("REVOKED"))
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
            "approval-group-revisions-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    class NoBackgroundWorkProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "quarkus.scheduler.enabled" to "false",
        )
    }

    private companion object {
        lateinit var grantId: String
    }
}
