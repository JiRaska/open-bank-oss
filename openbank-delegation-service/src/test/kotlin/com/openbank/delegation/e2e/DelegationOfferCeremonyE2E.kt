// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.e2e

import com.openbank.delegation.application.port.out.GrantorAuthorityVerdict
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.domain.model.DelegationResourceType
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
 * The ADR-0232 offer ceremony over the real HTTP surface against real Postgres + Valkey:
 * idempotency, preview purity, and the gate chain (ownership -> authority -> eligibility -> SCA),
 * where SCA spends LAST and exactly once. The four downstream ports are profile-local in-memory
 * alternatives (see [E2EStubs]) — the real wire shapes are the pact suite's subject, while THIS
 * suite proves gate ORDER and ATOMICITY (a refused offer must not spend the customer's ceremony).
 */
@QuarkusTest
@TestProfile(E2EProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class, restrictToAnnotatedClass = true)
class DelegationOfferCeremonyE2E : DelegationE2EBase() {

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
    fun `offer mints an OFFERED grant idempotently and replays verbatim`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val resourceId = UUID.randomUUID()
        val sca = UUID.randomUUID()
        scaStub.seed(sca, grantor, SCA_PURPOSE_GRANT)
        val requestId = UUID.randomUUID().toString()

        val first = offerSpec(grantor, grantee, resourceId, sca, requestId = requestId)
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
            .extract()
        val id = UUID.fromString(first.jsonPath().getString("id"))
        assertThat(first.jsonPath().getString("status")).isEqualTo("OFFERED")
        assertThat(first.jsonPath().getString("grantorName")).startsWith("Grantor")
        assertThat(first.jsonPath().getString("granteeName")).startsWith("Party")
        assertThat(first.jsonPath().getList<String>("capabilities")).contains("ACCOUNT_INITIATE_PAYMENT")
        assertThat(scaStub.isSpent(sca)).isTrue()

        // Same key + same body replays the stored response verbatim.
        val replay = offerSpec(grantor, grantee, resourceId, sca, requestId = requestId)
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
            .header("X-Idempotency-Replayed", "true")
            .extract()
        assertThat(UUID.fromString(replay.jsonPath().getString("id"))).isEqualTo(id)

        // Same key + different body refuses the fingerprint mismatch (409, #10922).
        offerSpec(grantor, grantee, UUID.randomUUID(), sca, requestId = requestId)
            .post("/api/v1/delegations")
            .then()
            .statusCode(409)
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
    fun `preview validates a draft without consuming SCA or persisting anything`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val resourceId = UUID.randomUUID()
        val sca = UUID.randomUUID()
        scaStub.seed(sca, grantor, SCA_PURPOSE_GRANT)

        val preview = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantor.toString())
            .header("X-Customer-Actor-Party-Id", grantor.toString())
            .body(previewBody(grantor, grantee, resourceId))
            .post("/api/v1/delegations/preview")
            .then()
            .statusCode(200)
            .extract()
        assertThat(preview.jsonPath().getBoolean("valid")).isTrue()
        assertThat(preview.jsonPath().getString("scaReference")).contains("delegation-grant-v1")
        assertThat(scaStub.isSpent(sca)).isFalse()
        assertThat(grantCountFor(grantor, grantee)).isZero()
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
    fun `ownership gate fails closed and refuses before SCA is spent`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val strangerResource = UUID.randomUUID()
        val unverifiableResource = UUID.randomUUID()
        val ownedResource = UUID.randomUUID()
        val scaNotOwned = UUID.randomUUID()
        val scaUnverifiable = UUID.randomUUID()
        val scaOwned = UUID.randomUUID()
        scaStub.seed(scaNotOwned, grantor, SCA_PURPOSE_GRANT)
        scaStub.seed(scaUnverifiable, grantor, SCA_PURPOSE_GRANT)
        scaStub.seed(scaOwned, grantor, SCA_PURPOSE_GRANT)
        ownershipStub.refuse(grantor, DelegationResourceType.ACCOUNT, strangerResource, OwnershipVerdict.NOT_OWNED)
        ownershipStub.refuse(
            grantor,
            DelegationResourceType.ACCOUNT,
            unverifiableResource,
            OwnershipVerdict.UNVERIFIABLE,
        )

        offerSpec(grantor, grantee, strangerResource, scaNotOwned)
            .post("/api/v1/delegations")
            .then()
            .statusCode(422)
        assertThat(scaStub.isSpent(scaNotOwned)).isFalse()

        // UNVERIFIABLE is a refusal too: an ownership lookup we could not perform is not permission.
        offerSpec(grantor, grantee, unverifiableResource, scaUnverifiable)
            .post("/api/v1/delegations")
            .then()
            .statusCode(422)
        assertThat(scaStub.isSpent(scaUnverifiable)).isFalse()

        offerSpec(grantor, grantee, ownedResource, scaOwned)
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
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
    fun `authority and eligibility gates refuse with the right status before SCA is spent`() {
        val grantor = UUID.randomUUID()
        val inactiveGrantee = UUID.randomUUID()
        val basicGrantee = UUID.randomUUID()
        val readOnlyGrantee = UUID.randomUUID()
        authorityStub.refuse(grantor, GrantorAuthorityVerdict.DENIED)
        eligibilityStub.seed(
            PartyEligibility(inactiveGrantee, active = false, kycLevel = "FULL", displayName = "Gone"),
        )
        eligibilityStub.seed(
            PartyEligibility(basicGrantee, active = true, kycLevel = "BASIC", displayName = "Basic"),
        )
        eligibilityStub.seed(
            PartyEligibility(readOnlyGrantee, active = true, kycLevel = "BASIC", displayName = "Reader"),
        )

        // Grantor authority DENIED -> 403, and the challenge is untouched. (Each refusal block
        // uses its OWN grantor: the authority refusal is keyed to the principal, and the later
        // blocks must reach eligibility and SCA with a clean authority verdict.)
        val deniedSca = UUID.randomUUID()
        scaStub.seed(deniedSca, grantor, SCA_PURPOSE_GRANT)
        offerSpec(grantor, UUID.randomUUID(), UUID.randomUUID(), deniedSca)
            .post("/api/v1/delegations")
            .then()
            .statusCode(403)
        assertThat(scaStub.isSpent(deniedSca)).isFalse()

        val inactiveGrantor = UUID.randomUUID()
        val inactiveSca = UUID.randomUUID()
        scaStub.seed(inactiveSca, inactiveGrantor, SCA_PURPOSE_GRANT)
        offerSpec(inactiveGrantor, inactiveGrantee, UUID.randomUUID(), inactiveSca)
            .post("/api/v1/delegations")
            .then()
            .statusCode(422)
        assertThat(scaStub.isSpent(inactiveSca)).isFalse()

        // Execution capability on a BASIC-KYC grantee is a 422 ...
        val execGrantor = UUID.randomUUID()
        val execSca = UUID.randomUUID()
        scaStub.seed(execSca, execGrantor, SCA_PURPOSE_GRANT)
        offerSpec(execGrantor, basicGrantee, UUID.randomUUID(), execSca)
            .post("/api/v1/delegations")
            .then()
            .statusCode(422)

        // ... while a read-only capability on a BASIC-KYC grantee passes (KYC_RANK boundary).
        val readGrantor = UUID.randomUUID()
        val readSca = UUID.randomUUID()
        scaStub.seed(readSca, readGrantor, SCA_PURPOSE_GRANT)
        offerSpec(
            readGrantor,
            readOnlyGrantee,
            UUID.randomUUID(),
            readSca,
            capabilities = readOnlyCapabilities(),
            limitClause = "",
        )
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
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
    fun `grant SCA binds party and purpose and spends exactly once`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val resourceId = UUID.randomUUID()

        // Unknown challenge: 400, not a crash and not a silent pass.
        offerSpec(grantor, grantee, resourceId, UUID.randomUUID())
            .post("/api/v1/delegations")
            .then()
            .statusCode(400)

        // A challenge bound to another party refuses and is NOT spent.
        val wrongParty = UUID.randomUUID()
        scaStub.seed(wrongParty, UUID.randomUUID(), SCA_PURPOSE_GRANT)
        offerSpec(grantor, grantee, resourceId, wrongParty)
            .post("/api/v1/delegations")
            .then()
            .statusCode(400)
        assertThat(scaStub.isSpent(wrongParty)).isFalse()

        // A challenge of the wrong purpose refuses and is NOT spent — the gate order means a
        // refused ceremony costs the customer nothing. (A challenge's purpose is fixed at
        // sca-service; an ACCEPT ceremony can never authorise a grant.)
        val wrongPurpose = UUID.randomUUID()
        scaStub.seed(wrongPurpose, grantor, SCA_PURPOSE_ACCEPT)
        offerSpec(grantor, grantee, resourceId, wrongPurpose)
            .post("/api/v1/delegations")
            .then()
            .statusCode(400)
        assertThat(scaStub.isSpent(wrongPurpose)).isFalse()

        // The right-purpose challenge mints the grant and is spent by it ...
        val grantSca = UUID.randomUUID()
        scaStub.seed(grantSca, grantor, SCA_PURPOSE_GRANT)
        offerSpec(grantor, grantee, resourceId, grantSca)
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
        assertThat(scaStub.isSpent(grantSca)).isTrue()

        // ... and a spent challenge is single-use (RTS Art. 5): a second grant on it refuses 400.
        offerSpec(grantor, UUID.randomUUID(), UUID.randomUUID(), grantSca)
            .post("/api/v1/delegations")
            .then()
            .statusCode(400)
    }

    @Test
    @Order(6)
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = EDGE_SUB),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE_PRINCIPAL),
        ],
    )
    fun `ADR-0249 ceiling rules refuse unenforced grant shapes`() {
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()

        // A money-moving capability with no cumulative ceiling is refused: unlimited by omission
        // is never granted (ADR-0249 D5). The refusal lands before SCA, so the ceremony survives.
        val noCeilingSca = UUID.randomUUID()
        scaStub.seed(noCeilingSca, grantor, SCA_PURPOSE_GRANT)
        offerSpec(grantor, grantee, UUID.randomUUID(), noCeilingSca, limitClause = "")
            .post("/api/v1/delegations")
            .then()
            .statusCode(400)
        assertThat(scaStub.isSpent(noCeilingSca)).isFalse()

        // A cumulative ceiling on a grant with no money-moving capability is equally refused:
        // nothing reserves spend against it, so the ceiling would govern nothing (ADR-0249 D3).
        val readOnlyCeilingSca = UUID.randomUUID()
        scaStub.seed(readOnlyCeilingSca, grantor, SCA_PURPOSE_GRANT)
        offerSpec(
            grantor,
            grantee,
            UUID.randomUUID(),
            readOnlyCeilingSca,
            capabilities = readOnlyCapabilities(),
            limitClause = EXECUTION_LIMITS_CLAUSE,
        )
            .post("/api/v1/delegations")
            .then()
            .statusCode(400)

        // The enforced shape passes: money-moving capability AND both cumulative ceilings.
        val enforcedSca = UUID.randomUUID()
        scaStub.seed(enforcedSca, grantor, SCA_PURPOSE_GRANT)
        val enforced = offerSpec(grantor, grantee, UUID.randomUUID(), enforcedSca)
            .post("/api/v1/delegations")
            .then()
            .statusCode(201)
            .extract()
        assertThat(enforced.jsonPath().getString("dailyLimit.currency")).isEqualTo("CZK")
        assertThat(enforced.jsonPath().getDouble("monthlyLimit.amount")).isEqualTo(50000.0)
    }
}
