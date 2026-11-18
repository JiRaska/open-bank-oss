// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The identity-resolution journey over the real HTTP surface: a namesake query on the empty
 * registry resolves to NO_MATCH, an operator registers the identity, and the same attributes a
 * second time escalate to a four-eyes verification case — where the first approver cannot cast
 * the second vote and a distinct second approver's LINK_TO_EXISTING verdict makes the resolver
 * answer MATCH_EXISTING.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "applicant resolves, duplicate is adjudicated, resolver learns the link" story, so a wiring
 * regression that deregisters a route (#3371), lets one approver decide twice, or resolves a
 * linked applicant to a fresh party fails here even when every property-level assertion stays
 * green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "approver-1", roles = ["ROLE_OPERATOR"])
class IdentityResolutionJourneyE2E {

    private val givenName = "E2e${UUID.randomUUID().toString().take(8).replace("-", "")}"
    private val familyName = "Resolve${UUID.randomUUID().toString().take(8).replace("-", "")}"
    private val birthdate = "1990-03-15"
    private lateinit var partyId: String
    private lateinit var caseId: String

    @Test
    @Order(1)
    fun `1 - a namesake query on the empty registry resolves to no match`() {
        Given {
            contentType("application/json")
            body(resolveBody())
        } When {
            post("/api/v1/parties/resolve")
        } Then {
            statusCode(200)
            body("decision", equalTo("NO_MATCH"))
            body("partyId", equalTo(null))
        }
    }

    @Test
    @Order(2)
    fun `2 - an operator registers the identity into the resolver index`() {
        val registered = Given {
            contentType("application/json")
            body(
                """
                {"partyId":"${UUID.randomUUID()}","givenName":"$givenName",
                "familyName":"$familyName","birthdate":"$birthdate"}
                """.trimIndent().replace("\n", ""),
            )
        } When {
            post("/api/v1/parties/register-identity")
        } Then {
            statusCode(200)
            body("coreAttributes.familyName", equalTo(familyName))
        } Extract { path<String>("id") }
        partyId = registered
    }

    @Test
    @Order(3)
    fun `3 - the same attributes now escalate to a four-eyes verification case`() {
        val escalated = Given {
            contentType("application/json")
            body(resolveBody())
        } When {
            post("/api/v1/parties/resolve")
        } Then {
            statusCode(200)
            body("decision", equalTo("NEEDS_MANUAL_VERIFICATION"))
            body("caseId", notNullValue())
            body("candidates[0].partyId", equalTo(partyId))
        } Extract { path<String>("caseId") }
        caseId = escalated
    }

    @Test
    @Order(4)
    fun `4 - the first approver cannot cast the second vote`() {
        Given {
            contentType("application/json")
            body(decisionBody())
        } When {
            post("/api/v1/parties/cases/$caseId/decision")
        } Then {
            statusCode(200)
            body("firstVerdict", equalTo("LINK_TO_EXISTING"))
        }

        Given {
            contentType("application/json")
            body(decisionBody())
        } When {
            post("/api/v1/parties/cases/$caseId/decision")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "approver-2", roles = ["ROLE_OPERATOR"])
    fun `5 - a distinct second approver concurs and the resolver links the applicant`() {
        Given {
            contentType("application/json")
            body(decisionBody())
        } When {
            post("/api/v1/parties/cases/$caseId/decision")
        } Then {
            statusCode(200)
            body("secondApprover", equalTo("approver-2"))
        }

        Given {
            contentType("application/json")
            body(resolveBody())
        } When {
            post("/api/v1/parties/resolve")
        } Then {
            statusCode(200)
            body("decision", equalTo("MATCH_EXISTING"))
            body("partyId", equalTo(partyId))
        }
    }

    @Test
    @Order(6)
    fun `6 - a resolve request missing its family name is a bad request`() {
        Given {
            contentType("application/json")
            body("""{"givenName":"$givenName","birthdate":"$birthdate"}""")
        } When {
            post("/api/v1/parties/resolve")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `7 - a viewer cannot register or resolve identities`() {
        Given {
            contentType("application/json")
            body(resolveBody())
        } When {
            post("/api/v1/parties/resolve")
        } Then {
            statusCode(403)
        }
    }

    private fun resolveBody() = """
        {"givenName":"$givenName","familyName":"$familyName","birthdate":"$birthdate"}
    """.trimIndent()

    private fun decisionBody() = """
        {"verdict":"LINK_TO_EXISTING","linkPartyId":"$partyId","notes":"same person, e2e"}
    """.trimIndent()
}
