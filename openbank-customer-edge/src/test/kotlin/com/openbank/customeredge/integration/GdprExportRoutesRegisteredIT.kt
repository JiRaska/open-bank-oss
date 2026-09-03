// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.integration

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

private const val GDPR_PARTY_ID = "5f2a1c30-6d7e-4a11-9c88-2b3d4e5f6a71"

/**
 * The two GDPR self-service routes must actually be SERVED (#8421).
 *
 * A unit test that calls the resource class cannot tell a served route from an unserved one — this
 * repo has shipped an entire endpoint that 404'd on every request because a top-level function
 * between `@Path` and its class stole the annotation, while the unit test calling the class stayed
 * green. Only real HTTP discriminates, so these drive RESTEasy.
 *
 * The assertion is deliberately "not 404 / not 405", not a status code: no party-service exists in
 * this JVM, so `UpstreamClient` cannot complete and maps its own failure to 502. What is under test
 * is registration and reachability — that a customer token gets past `@RolesAllowed` and the PDP
 * and into the proxy — not the upstream's answer, which `GdprSubjectAccessViaEdgeIT` in
 * party-service covers instead.
 *
 * The last test is the known-negative: a sibling path under the same prefix that was never declared
 * MUST 404. Without it "not 404" would be a claim the harness could satisfy by never routing
 * anything properly at all. Measured before the routes existed: the first two saw 404, the control
 * saw 404 — indistinguishable, which is the point.
 */
@QuarkusTest
@TestSecurity(user = "customer:$GDPR_PARTY_ID", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = GDPR_PARTY_ID)])
class GdprExportRoutesRegisteredIT {

    @Test
    fun `the Art 15 export route is registered and reachable by a customer token`() {
        Given { this } When { get("/customer/v1/privacy/gdpr-export") } Then {
            statusCode(not404NorMethodMismatch())
        }
    }

    @Test
    fun `the Art 20 portability route is registered and reachable by a customer token`() {
        Given { this } When { get("/customer/v1/privacy/portability-export") } Then {
            statusCode(not404NorMethodMismatch())
        }
    }

    @Test
    fun `an undeclared sibling under the same prefix still 404s (known-negative control)`() {
        Given { this } When { get("/customer/v1/privacy/no-such-export") } Then { statusCode(404) }
    }

    private fun not404NorMethodMismatch() = org.hamcrest.Matchers.allOf(
        org.hamcrest.Matchers.not(404),
        org.hamcrest.Matchers.not(405),
    )
}
