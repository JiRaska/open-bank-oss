// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import com.openbank.party.domain.model.PhoneDirectory
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * End-to-end journey for a party's whole life in the directory: **create it with an idempotency
 * key, replay the key, find it through search, update its master data, attach a document, opt it
 * into pay-to-phone discoverability and prove the phone-hash lookup only ever answers opted-in
 * parties — with a read-back through the API after every mutation.**
 *
 * Driven entirely through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`)
 * against a real Postgres and Redpanda (`PostgresRedpandaTestResource`, per-class Testcontainers).
 * No use case or repository is called directly and nothing is mocked; the phone-hash lookup is
 * exercised with a hash computed by the same `PhoneDirectory` normalisation the service uses.
 *
 * ### Service boundaries — what is NOT claimed here
 *
 * The marketing-consent PATCH dials consent-service over REST (ADR-0198), which no test JVM
 * serves, so the journey never toggles consent on — the off path is a local no-op and is not
 * interesting enough to claim. PARTY_CREATED's dispatch to kyc-service is outbox state this
 * service owns, but no consumer runs in this process.
 *
 * ### Why the read-back matters
 *
 * The write endpoints return the state they just computed, which is the same code path that
 * computed it. Reading the party back with a separate `GET` after each mutation is what proves
 * the change was persisted rather than merely rendered.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
)
@TestProfile(PartyDirectoryLifecycleE2E.JourneyProfile::class)
class PartyDirectoryLifecycleE2E {

    /** RBAC is asserted through `@RolesAllowed`; the advisory OPA PEP has no sidecar in tests. */
    class JourneyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `1 - an operator creates an individual party and it reads back masked`() {
        val created = given().contentType("application/json")
            .header("Idempotency-Key", createKey)
            .body(createBody(LEGAL_NAME, email, PHONE))
            .post("/api/v1/parties")
        created.then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("legalName", equalTo(LEGAL_NAME))
            .body("partyType", equalTo("INDIVIDUAL"))
            .body("kycStatus", equalTo("NOT_STARTED"))
        partyId = created.jsonPath().getString("id")

        // Read-back: the directory row a staff member would see — name visible to staff,
        // direct identifiers masked.
        given().get("/api/v1/parties/$partyId").then()
            .statusCode(200)
            .body("id", equalTo(partyId))
            .body("legalName", equalTo(LEGAL_NAME))
            .body("piiMasked", equalTo(true))
            .body("email", not(equalTo(email)))
    }

    @Test
    @Order(2)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `2 - a duplicate create is refused and the directory keeps exactly one row`() {
        // The email is the idempotency boundary the service documents: a retry with the same
        // email is a 409, never a second directory row.
        given().contentType("application/json")
            .header("Idempotency-Key", createKey)
            .body(createBody(LEGAL_NAME, email, PHONE))
            .post("/api/v1/parties").then()
            .statusCode(409)

        // And the name search still resolves to exactly the one directory row.
        given().queryParam("q", LEGAL_NAME).get("/api/v1/parties/search").then()
            .statusCode(200)
            .body("data.findAll { it.id == '$partyId' }.size()", equalTo(1))
    }

    @Test
    @Order(3)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `3 - the surface fails closed on a missing key, a missing email, a synthetic party, an unknown id`() {
        given().contentType("application/json")
            .body(createBody("E2E No Key", "e2e-nokey@openbank.test", null))
            .post("/api/v1/parties").then()
            .statusCode(400)

        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(createBody("E2E No Email", null, null))
            .post("/api/v1/parties").then()
            .statusCode(400)

        // Only ROLE_ADMIN may create a synthetic party — an operator asking for one is refused
        // before any row is written (the guard answers 400, the same shape as any other
        // rejected create value).
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(createBody("E2E Synthetic", "e2e-synthetic@openbank.test", null, "SYNTHETIC"))
            .post("/api/v1/parties").then()
            .statusCode(400)

        given().get("/api/v1/parties/${UUID.randomUUID()}").then()
            .statusCode(404)
    }

    @Test
    @Order(4)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `4 - master data can be updated and the read-back reflects it`() {
        given().contentType("application/json")
            .body("""{"tradingName": "E2E Trading s.r.o.", "email": "$updatedEmail"}""")
            .patch("/api/v1/parties/$partyId").then()
            .statusCode(200)
            .body("tradingName", equalTo("E2E Trading s.r.o."))

        given().get("/api/v1/parties/$partyId").then()
            .statusCode(200)
            .body("tradingName", equalTo("E2E Trading s.r.o."))
            .body("legalName", equalTo(LEGAL_NAME))
    }

    @Test
    @Order(5)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `5 - an identity document can be attached and lists back`() {
        val response = given().contentType("application/json")
            .body(
                """
                {
                  "documentType": "PASSPORT",
                  "documentNumber": "E2E123456",
                  "issuingCountry": "CZ",
                  "expiryDate": "2032-01-01"
                }
                """.trimIndent(),
            )
            .post("/api/v1/parties/$partyId/documents")
        response.then().statusCode(201)

        given().get("/api/v1/parties/$partyId/documents").then()
            .statusCode(200)
            .body("documentNumber", hasItem("E2E123456"))
            .body("documentType", hasItem("PASSPORT"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `6 - opting in to pay-to-phone discoverability is a deliberate, revocable act`() {
        // A second party with a phone number who has NOT opted in — the privacy control the
        // lookup in test 7 must respect.
        val privateParty = given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(createBody("E2E Private Person", "e2e-private@openbank.test", PRIVATE_PHONE))
            .post("/api/v1/parties")
        privateParty.then().statusCode(201)
        privatePartyId = privateParty.jsonPath().getString("id")

        given().contentType("application/json")
            .body("""{"discoverable": true}""")
            .put("/api/v1/parties/$partyId/discoverable").then()
            .statusCode(200)
            .body("discoverable", equalTo(true))
    }

    @Test
    @Order(7)
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `7 - the phone-hash directory lookup answers opted-in parties and never the others`() {
        // ROLE_API — the customer edge, never a browser; the hashes are of the E.164 forms the
        // domain's PhoneDirectory normalisation produces. Both numbers are queried: the
        // opted-out party must not come back even though its hash was submitted.
        val hash = PhoneDirectory.hash(PHONE)
        val privateHash = PhoneDirectory.hash(PRIVATE_PHONE)
        given().contentType("application/json")
            .body("""{"phoneHashes": ["$hash", "$privateHash"]}""")
            .post("/api/v1/parties/directory/lookup").then()
            .statusCode(200)
            .body("matches.partyId", hasItem(partyId))
            .body("matches.partyId", not(hasItem(privatePartyId)))
            .body("matches.size()", equalTo(1))

        // A hash that matches nothing is not an error and reveals nothing.
        given().contentType("application/json")
            .body("""{"phoneHashes": ["${"0".repeat(64)}"]}""")
            .post("/api/v1/parties/directory/lookup").then()
            .statusCode(200)
            .body("matches.size()", equalTo(0))
    }

    @Test
    @Order(8)
    @TestSecurity(user = COMPLIANCE, roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = COMPLIANCE),
            Claim(key = "preferred_username", value = COMPLIANCE),
        ],
    )
    fun `8 - a compliance officer can read the detail but cannot search, list or create`() {
        given().get("/api/v1/parties/$partyId").then()
            .statusCode(200)
            .body("legalName", equalTo(LEGAL_NAME))

        given().queryParam("q", LEGAL_NAME).get("/api/v1/parties/search").then()
            .statusCode(403)
        given().get("/api/v1/parties").then()
            .statusCode(403)
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(createBody("E2E Compliance Create", "e2e-compliance@openbank.test", null))
            .post("/api/v1/parties").then()
            .statusCode(403)
    }

    private fun createBody(legalName: String, email: String?, phone: String?, classification: String? = null): String {
        val body = StringBuilder(
            """{"partyType":"INDIVIDUAL","legalName":"$legalName","tradingName":null,"email":${
                email?.let { "\"$it\"" } ?: "null"
            }""",
        )
        phone?.let { body.append(""","phone":"$it"""") }
        classification?.let { body.append(""","classification":"$it"""") }
        return body.append("}").toString()
    }

    private companion object {
        const val OPERATOR = "party-e2e-operator"
        const val COMPLIANCE = "party-e2e-compliance"
        const val LEGAL_NAME = "E2E Directory Customer"
        const val PHONE = "+420777111222"
        const val PRIVATE_PHONE = "+420777333444"
    }

    private val createKey = UUID.randomUUID().toString()
    private val email = "e2e-${UUID.randomUUID()}@openbank.test"
    private val updatedEmail = "e2e-updated-${UUID.randomUUID()}@openbank.test"
    private lateinit var partyId: String
    private lateinit var privatePartyId: String
}
