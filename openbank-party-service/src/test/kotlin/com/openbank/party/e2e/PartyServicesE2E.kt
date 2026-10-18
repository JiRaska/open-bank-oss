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
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The servicing half of the party journey over real HTTP against real Postgres + Redpanda:
 * saved payees with their ceiling, the pay-to-phone directory opt-in, representation mandates,
 * the RČ dedup gate, and the read-side role boundary (search, list, the ROLE_RISK exception on
 * the detail read). Directory matches are re-verified against the stored `phone_hash` so the
 * suite proves the privacy control end to end: a number that never opted in never appears.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
)
@Suppress("LongMethod")
class PartyServicesE2E : PartyE2EBase() {

    // ----------------------------------------------------------------------------------------------
    // Saved payees
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
    fun `payees normalize iban reject blank fields enforce the 30 payee limit and free the slot on delete`() {
        val partyId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        val spacedIban = "cz65 0800 0000 1920 0014 5399"
        val normalized = "CZ6508000000192000145399"
        Given {
            spec(jsonSpec(payeeBody("First Payee", spacedIban)))
        } When {
            put("/api/v1/parties/$partyId/payees")
        } Then {
            statusCode(200)
            body("iban", equalTo(normalized))
        }

        Given {
            spec(jsonSpec(payeeBody(" ", normalized)))
        } When {
            put("/api/v1/parties/$partyId/payees")
        } Then {
            statusCode(400)
            body("code", equalTo("MISSING_FIELD"))
        }

        // Fill the book to the 30-payee ceiling, then one more must be rejected with 422.
        for (i in 2..30) {
            Given {
                spec(jsonSpec(payeeBody("Payee $i", "DE8937040044053201300${i.toString().padStart(2, '0')}")))
            } When {
                put("/api/v1/parties/$partyId/payees")
            } Then {
                statusCode(200)
            }
        }

        Given {
            spec(jsonSpec(payeeBody("One Too Many", "FR1420041010050500013M02606")))
        } When {
            put("/api/v1/parties/$partyId/payees")
        } Then {
            statusCode(422)
            body("code", equalTo("PAYEE_LIMIT_EXCEEDED"))
        }

        Given { this } When {
            delete("/api/v1/parties/$partyId/payees/$normalized")
        } Then {
            statusCode(204)
        }

        // The deleted IBAN's slot is free again.
        Given {
            spec(jsonSpec(payeeBody("Replacement", "FR1420041010050500013M02606")))
        } When {
            put("/api/v1/parties/$partyId/payees")
        } Then {
            statusCode(200)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Pay-to-phone directory
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = "service-account-openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `directory lookup matches only opted-in discoverable parties and drops malformed hashes`() {
        val visibleId = Given {
            spec(
                createSpec(
                    createPartyBody("e2e-${UUID.randomUUID()}@example.test", phone = "+420777123456"),
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

        // A second party holds a number but never opted in: discoverable defaults to false.
        val hiddenId = Given {
            spec(
                createSpec(
                    createPartyBody("e2e-${UUID.randomUUID()}@example.test", phone = "+420777999888"),
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

        Given {
            spec(jsonSpec("{\"discoverable\":true}"))
        } When {
            put("/api/v1/parties/$visibleId/discoverable")
        } Then {
            statusCode(200)
            body("discoverable", equalTo(true))
        }

        val visibleHash = partyField(visibleId, "phone_hash")
        val hiddenHash = partyField(hiddenId, "phone_hash")
        check(visibleHash != null && hiddenHash != null) { "phone hashes must be computed at insert" }

        // A null element is a malformed document, not a miss — the whole request is rejected
        // with 400 even though the other hashes are well-formed.
        Given {
            spec(jsonSpec(directoryLookupBody(visibleHash, hiddenHash, "not-a-hex-hash", null)))
        } When {
            post("/api/v1/parties/directory/lookup")
        } Then {
            statusCode(400)
        }

        // The opt-in party matches by hash; the opted-out one never appears.
        val matchIds = Given {
            spec(jsonSpec(directoryLookupBody(visibleHash, hiddenHash)))
        } When {
            post("/api/v1/parties/directory/lookup")
        } Then {
            statusCode(200)
            body("matches.partyId", hasItem(visibleId.toString()))
        } Extract {
            jsonPath().getList<String>("matches.partyId")
        }
        assertThat(matchIds).doesNotContain(hiddenId.toString())
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
    fun `directory lookup is closed to non API callers`() {
        Given {
            spec(jsonSpec(directoryLookupBody("a".repeat(64))))
        } When {
            post("/api/v1/parties/directory/lookup")
        } Then {
            statusCode(403)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Representation mandates
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
    fun `mandates bind an agent to a company and reject individual principals and incomplete joint authority`() {
        val companyId = Given {
            spec(
                createSpec(
                    createPartyBody("e2e-corp-${UUID.randomUUID()}@example.test", partyType = "COMPANY"),
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

        val agentId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        Given {
            spec(jsonSpec(mandateBody(agentId, role = "LEGAL_REPRESENTATIVE", authority = "SOLE")))
        } When {
            post("/api/v1/parties/$companyId/mandates")
        } Then {
            statusCode(201)
            body("role", equalTo("LEGAL_REPRESENTATIVE"))
            body("authority", equalTo("SOLE"))
            body("requiredSignatures", equalTo(1))
            body("status", equalTo("ACTIVE"))
        }

        Given { this } When {
            get("/api/v1/parties/$companyId/mandates")
        } Then {
            statusCode(200)
            body("agentPartyId", hasItem(agentId.toString()))
        }

        // The profile switcher reads the agent-side view: the agent acts for the company.
        Given { this } When {
            get("/api/v1/parties/$agentId/acting-for")
        } Then {
            statusCode(200)
            body("partyId", hasItem(companyId.toString()))
        }

        // An individual cannot be a mandate principal (422 — well-formed, not applicable).
        Given {
            spec(jsonSpec(mandateBody(companyId)))
        } When {
            post("/api/v1/parties/$agentId/mandates")
        } Then {
            statusCode(422)
            body("error", equalTo("MANDATE_REJECTED"))
        }

        // JOINT authority without requiredSignatures is a client error, not a rejection.
        Given {
            spec(jsonSpec(mandateBody(agentId, authority = "JOINT")))
        } When {
            post("/api/v1/parties/$companyId/mandates")
        } Then {
            statusCode(400)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // RČ dedup gate and search
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = "service-account-openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `resolve fails closed to 503 while the dedup pepper is unconfigured`() {
        Given {
            spec(jsonSpec("{\"rc\":\"1234567890\"}"))
        } When {
            post("/api/v1/parties/resolve")
        } Then {
            statusCode(503)
            body("code", equalTo("DEDUP_UNAVAILABLE"))
        }
    }

    @Test
    @TestSecurity(user = "party-e2e-risk", roles = ["ROLE_RISK"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-risk"),
            Claim(key = "preferred_username", value = "party-e2e-risk"),
        ],
    )
    fun `risk sees the party detail but never the list or search`() {
        // ROLE_RISK holds no party-create role: seed the subject row directly (the same minimal
        // insert GdprSubjectAccessViaEdgeIT uses) and exercise only the boundary being tested.
        val partyId = UUID.randomUUID()
        insertParty(partyId, "e2e-${UUID.randomUUID()}@example.test", "E2E Risk Subject ${UUID.randomUUID()}")

        Given { this } When {
            get("/api/v1/parties/$partyId")
        } Then {
            statusCode(200)
        }

        Given { this } When {
            get("/api/v1/parties")
        } Then {
            statusCode(403)
        }

        Given { this } When {
            get("/api/v1/parties/search?q=whatever")
        } Then {
            statusCode(403)
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
    fun `search finds a party by name and treats a one character term as an empty page`() {
        // ROLE_VIEWER holds no party-create role: seed the named row directly and exercise
        // only the search surface being tested.
        val name = "E2E Vodnik ${UUID.randomUUID()}"
        insertParty(UUID.randomUUID(), "e2e-${UUID.randomUUID()}@example.test", name)

        Given { queryParam("q", name) } When {
            get("/api/v1/parties/search")
        } Then {
            statusCode(200)
            body("data.legalName", hasItem(name))
        }

        // Shorter than MIN_TERM_LENGTH: collapses to a wildcard (list) shape, which search
        // deliberately answers with an empty page rather than an unselective scan.
        Given { this } When {
            get("/api/v1/parties/search?q=V")
        } Then {
            statusCode(200)
            body("data.size()", equalTo(0))
        }
    }
}
