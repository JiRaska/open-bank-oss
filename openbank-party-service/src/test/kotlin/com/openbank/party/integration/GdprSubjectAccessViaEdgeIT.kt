// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.infrastructure.rest.PartyResource
import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * GDPR Art. 15 / Art. 20 must be reachable by the only channel a retail customer has (#8421).
 *
 * ## What was broken
 *
 * Both exports accept ROLE_ADMIN, ROLE_DPO, or the subject's own JWT. customer-edge forwards none
 * of those: it validates the customer token in the `openbank-customers` realm and calls upstream
 * with its OWN client_credentials token from the operator realm (`UpstreamClient` KDoc), so
 * party-service saw `service-account-openbank-edge` holding ROLE_OPERATOR alone
 * (`realm-template.json`) — all three branches false, every subject-initiated export a 403.
 *
 * ## Why it has to be an IT
 *
 * The predicate reads `SecurityIdentity.principal.name` and a `@HeaderParam`, both supplied by the
 * container. `PartyResourceAuditTest` builds `PartyResource` by hand with a mocked identity and
 * passes the header itself, so it is structurally incapable of seeing either half. Measured against
 * the pre-fix handler (no header parameter, no edge branch): the first assertion below saw **403**.
 *
 * ## The controls
 *
 * The permissive assertion alone would pass against a handler that had simply stopped checking, so
 * every refusal it must still make is asserted in the same run: a header naming a different party,
 * an absent header, and — the load-bearing one — a caller that holds ROLE_OPERATOR but is not the
 * edge. Real staff carry ROLE_OPERATOR too, so if the identity match were dropped the fourth test
 * is the only one that would notice. The last test is the untouched-behaviour control.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class GdprSubjectAccessViaEdgeIT {

    private lateinit var subjectId: String

    /**
     * A real row, not a random UUID: `exportPartyData` throws `PartyNotFoundException` (404) before
     * any of this matters, and a 404 would let a still-broken 403 pass as "not 200" in only one
     * direction. kyc-service and card-issuance-service are absent here and
     * `GdprAggregationAdapter` degrades an unreachable hop to null/empty, so the export itself is
     * deterministic.
     */
    @BeforeEach
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun createSubject() {
        val payload = """
            {
              "partyType": "INDIVIDUAL",
              "legalName": "Art Fifteen",
              "email": "art.fifteen.${UUID.randomUUID()}@openbank.test",
              "phone": "+420777111333",
              "dateOfBirth": "1985-06-15",
              "nationality": "CZE"
            }
        """.trimIndent()
        subjectId = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(payload)
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        }.extract().path<String>("id")
    }

    @Test
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    fun `the customer edge may fetch the Art 15 export for the subject its header names`() {
        Given {
            header(PartyResource.CUSTOMER_PARTY_HEADER, subjectId)
        } When {
            get("/api/v1/parties/$subjectId/gdpr-export")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    fun `the customer edge may fetch the Art 20 portability export for the subject its header names`() {
        Given {
            header(PartyResource.CUSTOMER_PARTY_HEADER, subjectId)
        } When {
            get("/api/v1/parties/$subjectId/gdpr-portability-export")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    fun `a header naming a different party cannot export the party in the path`() {
        Given {
            header(PartyResource.CUSTOMER_PARTY_HEADER, UUID.randomUUID().toString())
        } When {
            get("/api/v1/parties/$subjectId/gdpr-export")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    fun `the edge principal without a party header is refused`() {
        Given { this } When { get("/api/v1/parties/$subjectId/gdpr-export") } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = "operator.novak", roles = ["ROLE_OPERATOR"])
    fun `a ROLE_OPERATOR caller that is not the edge is refused even with a matching header`() {
        Given {
            header(PartyResource.CUSTOMER_PARTY_HEADER, subjectId)
        } When {
            get("/api/v1/parties/$subjectId/gdpr-export")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "dpo.admin", roles = ["ROLE_ADMIN"])
    fun `an admin still exports without any header (unchanged behaviour control)`() {
        Given { this } When { get("/api/v1/parties/$subjectId/gdpr-export") } Then { statusCode(200) }
    }

    companion object {
        /**
         * `service-account-<clientId>` is what Keycloak puts in `preferred_username` for a
         * client_credentials token; `rest.rego` hardcodes this same string in three edge rules.
         */
        const val EDGE_PRINCIPAL = "service-account-openbank-edge"
    }
}
