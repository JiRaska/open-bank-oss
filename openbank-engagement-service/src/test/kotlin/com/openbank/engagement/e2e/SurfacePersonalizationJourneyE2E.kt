// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.e2e

import com.openbank.engagement.integration.StubConsentCheckPort
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The surface personalization journey over the real HTTP surface: an operator resolves the home
 * banner for a consenting party, sees the same party become not_eligible when consent is gone,
 * posts three dismissals that suppress the next resolve, and watches the edges refuse malformed
 * traffic — an unknown slot and an event for content outside the rendered catalogue.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "party resolves, interacts, and the surface adapts" story, so a wiring regression that
 * deregisters a route (#3371), serves a banner to a party without consent, or forgets the
 * dismissal counter fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_OPERATOR"])
class SurfacePersonalizationJourneyE2E {

    private val partyId = UUID.randomUUID()

    @BeforeEach
    fun grantConsent() = StubConsentCheckPort.granted.set(true)

    @Test
    @Order(1)
    fun `1 - an eligible party sees the catalogued banner`() {
        Given { queryParam("partyId", partyId.toString()) }
            .When { get("/api/v1/surfaces/HOME_BANNER") }
            .Then {
                statusCode(200)
                body("state", equalTo("ok"))
                body("content[0].id", equalTo("SAVINGS_RATE_BANNER"))
            }
    }

    @Test
    @Order(2)
    fun `2 - the same party without marketing consent is not eligible`() {
        StubConsentCheckPort.granted.set(false)
        Given { queryParam("partyId", partyId.toString()) }
            .When { get("/api/v1/surfaces/HOME_BANNER") }
            .Then {
                statusCode(200)
                body("state", equalTo("not_eligible"))
            }
    }

    @Test
    @Order(3)
    fun `3 - three posted dismissals suppress the next resolve`() {
        repeat(3) {
            Given {
                contentType("application/json")
                body(dismissBody(partyId))
            } When {
                post("/api/v1/surfaces/events")
            } Then {
                statusCode(202)
            }
        }

        Given { queryParam("partyId", partyId.toString()) }
            .When { get("/api/v1/surfaces/HOME_BANNER") }
            .Then {
                statusCode(200)
                body("state", equalTo("suppressed"))
            }
    }

    @Test
    @Order(4)
    fun `4 - an event for content outside the catalogue is rejected`() {
        Given {
            contentType("application/json")
            body(
                """
                {"partyId":"${UUID.randomUUID()}","contentId":"NOT_A_CATALOGUE_ITEM",
                "slot":"HOME_BANNER","type":"CLICK"}
                """.trimIndent().replace("\n", ""),
            )
        } When {
            post("/api/v1/surfaces/events")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @Order(5)
    fun `5 - an unknown slot is a bad request not a silent empty result`() {
        Given { queryParam("partyId", UUID.randomUUID().toString()) }
            .When { get("/api/v1/surfaces/NOT_A_REAL_SLOT") }
            .Then { statusCode(400) }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "viewer@openbank.test", roles = ["ROLE_VIEWER"])
    fun `6 - a viewer cannot resolve a surface`() {
        Given { queryParam("partyId", partyId.toString()) }
            .When { get("/api/v1/surfaces/HOME_BANNER") }
            .Then { statusCode(403) }
    }

    private fun dismissBody(party: UUID) = """
        {"partyId":"$party","contentId":"SAVINGS_RATE_BANNER","slot":"HOME_BANNER","type":"DISMISS"}
    """.trimIndent()
}
