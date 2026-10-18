// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The back-office onboarding half of the party journey over real HTTP against real Postgres +
 * Redpanda: creation with its caller guards and consent capture, the two-key KYC/AML activation
 * gate, and materiality-classified update evidence. Every state change is re-read over JDBC
 * (party row, `party_outbox` envelope) so the suite proves the durable evidence, not just the
 * HTTP surface.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
)
class PartyOnboardingE2E : PartyE2EBase() {

    @Inject
    lateinit var marketingConsent: E2EMarketingConsentForwarding

    // ----------------------------------------------------------------------------------------------
    // Creation
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "party-e2e-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-operator"),
            Claim(key = "preferred_username", value = "party-e2e-operator"),
        ],
    )
    fun `create captures onboarding consent and writes PARTY_CREATED evidence`() {
        val email = "e2e-${UUID.randomUUID()}@example.test"

        val partyId = Given {
            spec(
                createSpec(
                    createPartyBody(email, consentGdpr = true, consentMarketing = true),
                    "key-${UUID.randomUUID()}",
                ),
            )
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
            body("status", equalTo("PENDING_KYC"))
            body("kycStatus", equalTo("NOT_STARTED"))
            body("consentGdpr", equalTo(true))
            body("consentMarketing", equalTo(true))
            body("consentCapturedAt", notNullValue())
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        assertThat(outboxCount(partyId, "PARTY_CREATED")).isEqualTo(1)
        val payload = outboxPayload(partyId, "PARTY_CREATED")
        assertThat(payload["eventType"].asText()).isEqualTo("PARTY_CREATED")
        assertThat(payload["partyId"].asText()).isEqualTo(partyId.toString())
        assertThat(payload["status"].asText()).isEqualTo("PENDING_KYC")
        assertThat(payload["kycStatus"].asText()).isEqualTo("NOT_STARTED")
        assertThat(payload["email"].asText()).isEqualTo(email)
        assertThat(payload["actorType"].asText()).isEqualTo("SYSTEM")
    }

    @Test
    @TestSecurity(user = "party-e2e-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-operator"),
            Claim(key = "preferred_username", value = "party-e2e-operator"),
        ],
    )
    fun `duplicate email is 409 and reusing an explicit id with another email returns the same party`() {
        val explicitId = UUID.randomUUID()
        val email = "e2e-${UUID.randomUUID()}@example.test"

        Given {
            spec(createSpec(createPartyBody(email, id = explicitId), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        }

        // Same email under a different id: the email check runs first and conflicts.
        Given {
            spec(createSpec(createPartyBody(email), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(409)
        }

        // Same explicit id with a DIFFERENT email: returns the existing record, no second event.
        val reused = Given {
            spec(
                createSpec(
                    createPartyBody("e2e-${UUID.randomUUID()}@example.test", id = explicitId),
                    "key-${UUID.randomUUID()}",
                ),
            )
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        assertThat(reused).isEqualTo(explicitId)
        assertThat(outboxCount(explicitId, "PARTY_CREATED")).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "party-e2e-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-operator"),
            Claim(key = "preferred_username", value = "party-e2e-operator"),
        ],
    )
    fun `create guards reject missing idempotency key missing email and synthetic by non-admin`() {
        Given {
            spec(jsonSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test")))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(400)
        }

        Given {
            spec(createSpec("{\"partyType\":\"INDIVIDUAL\",\"legalName\":\"No Email\"}", "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(400)
        }

        Given {
            spec(
                createSpec(
                    createPartyBody("e2e-${UUID.randomUUID()}@example.test", classification = "SYNTHETIC"),
                    "key-${UUID.randomUUID()}",
                ),
            )
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-other", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-other"),
            Claim(key = "sub", value = "service-account-openbank-other"),
            Claim(key = "preferred_username", value = "service-account-openbank-other"),
        ],
    )
    fun `an unnamed service account cannot create a party through ROLE_API alone`() {
        Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(403)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Two-key KYC/AML gate
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "party-e2e-kyc", roles = ["ROLE_KYC"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-kyc"),
            Claim(key = "preferred_username", value = "party-e2e-kyc"),
        ],
    )
    fun `the two-key gate keeps an approved party pending until AML clears and suspends a rejection`() {
        val approvedId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        Given {
            spec(jsonSpec("{\"kycStatus\":\"APPROVED\"}"))
        } When {
            put("/api/v1/parties/$approvedId/kyc-status")
        } Then {
            statusCode(200)
            body("kycStatus", equalTo("APPROVED"))
            body("status", equalTo("PENDING_KYC"))
        }

        // KYC alone never activates: the second key (AML CLEARED) is still missing.
        assertThat(outboxCount(approvedId, "KYC_STATUS_CHANGED")).isEqualTo(1)
        val payload = outboxPayload(approvedId, "KYC_STATUS_CHANGED")
        assertThat(payload["eventType"].asText()).isEqualTo("KYC_STATUS_CHANGED")
        assertThat(payload["kycStatus"].asText()).isEqualTo("APPROVED")
        assertThat(payload["status"].asText()).isEqualTo("PENDING_KYC")

        val rejectedId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        Given {
            spec(jsonSpec("{\"kycStatus\":\"REJECTED\"}"))
        } When {
            put("/api/v1/parties/$rejectedId/kyc-status")
        } Then {
            statusCode(200)
            body("status", equalTo("SUSPENDED"))
        }

        Given {
            spec(jsonSpec("{\"kycStatus\":\"BOGUS\"}"))
        } When {
            put("/api/v1/parties/$approvedId/kyc-status")
        } Then {
            statusCode(400)
        }

        Given {
            spec(jsonSpec("{\"kycStatus\":\"APPROVED\"}"))
        } When {
            put("/api/v1/parties/${UUID.randomUUID()}/kyc-status")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "party-e2e-viewer", roles = ["ROLE_VIEWER"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-viewer"),
            Claim(key = "preferred_username", value = "party-e2e-viewer"),
        ],
    )
    fun `a viewer cannot move kyc status`() {
        Given {
            spec(jsonSpec("{\"kycStatus\":\"APPROVED\"}"))
        } When {
            put("/api/v1/parties/${UUID.randomUUID()}/kyc-status")
        } Then {
            statusCode(403)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Updates and their materiality evidence
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "party-e2e-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-operator"),
            Claim(key = "preferred_username", value = "party-e2e-operator"),
        ],
    )
    fun `contact edits are NON_MATERIAL and a legal name edit is MATERIAL with materialFields`() {
        val partyId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        Given {
            spec(jsonSpec(updatePartyBody(phone = "+420603111222")))
        } When {
            patch("/api/v1/parties/$partyId")
        } Then {
            statusCode(200)
            body("phone", equalTo("+420603111222"))
        }

        val immaterial = outboxPayload(partyId, "PARTY_UPDATED")
        assertThat(immaterial["materiality"].asText()).isEqualTo("NON_MATERIAL")
        assertThat(immaterial["materialFields"].map { it.asText() }).isEmpty()

        Given {
            spec(jsonSpec(updatePartyBody(legalName = "E2E Renamed ${UUID.randomUUID()}")))
        } When {
            patch("/api/v1/parties/$partyId")
        } Then {
            statusCode(200)
        }

        val material = outboxPayload(partyId, "PARTY_UPDATED")
        assertThat(material["materiality"].asText()).isEqualTo("MATERIAL")
        assertThat(material["materialFields"].map { it.asText() }).containsExactly("legalName")
    }

    @Test
    @TestSecurity(user = "party-e2e-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-operator"),
            Claim(key = "preferred_username", value = "party-e2e-operator"),
        ],
    )
    fun `marketing consent toggle forwards the grant and reports the accepted intent`() {
        val partyId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        Given {
            spec(jsonSpec("{\"marketingConsent\":true}"))
        } When {
            patch("/api/v1/parties/$partyId/consent")
        } Then {
            statusCode(200)
            body("consentMarketing", equalTo(true))
        }

        // The forward to consent-service happened synchronously (stub records it); the persisted
        // column belongs to the projection and is deliberately not asserted here.
        assertThat(marketingConsent.grants).containsKey(partyId)

        // Toggling off without a tracked consent is a no-op on the wire (nothing to revoke).
        Given {
            spec(jsonSpec("{\"marketingConsent\":false}"))
        } When {
            patch("/api/v1/parties/$partyId/consent")
        } Then {
            statusCode(200)
            body("consentMarketing", equalTo(false))
        }
        assertThat(marketingConsent.revocations).doesNotContainKey(partyId)
    }

    // ----------------------------------------------------------------------------------------------
    // Documents
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "party-e2e-kyc", roles = ["ROLE_KYC"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-kyc"),
            Claim(key = "preferred_username", value = "party-e2e-kyc"),
        ],
    )
    fun `identity documents round trip and an unknown document type is a 400`() {
        val partyId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        val documentNumber = "DOC-${UUID.randomUUID()}"
        Given {
            spec(
                jsonSpec(
                    "{\"documentType\":\"PASSPORT\",\"documentNumber\":\"$documentNumber\"," +
                        "\"issuingCountry\":\"CZ\"}",
                ),
            )
        } When {
            post("/api/v1/parties/$partyId/documents")
        } Then {
            statusCode(201)
            body("documentNumber", equalTo(documentNumber))
        }

        Given { this } When {
            get("/api/v1/parties/$partyId/documents")
        } Then {
            statusCode(200)
            body("documentNumber", hasItem(documentNumber))
        }

        Given {
            spec(
                jsonSpec(
                    "{\"documentType\":\"NO_SUCH_TYPE\",\"documentNumber\":\"X\",\"issuingCountry\":\"CZ\"}",
                ),
            )
        } When {
            post("/api/v1/parties/$partyId/documents")
        } Then {
            statusCode(400)
        }
    }
}
