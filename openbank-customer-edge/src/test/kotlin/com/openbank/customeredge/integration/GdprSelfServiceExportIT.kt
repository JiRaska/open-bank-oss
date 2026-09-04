// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.integration

import com.openbank.customeredge.contract.StubUpstreamResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val GDPR_PARTY_ID = "5f2a1c30-6d7e-4a11-9c88-2b3d4e5f6a71"
private const val ART15_PATH = "/api/v1/parties/$GDPR_PARTY_ID/gdpr-export"
private const val ART20_PATH = "/api/v1/parties/$GDPR_PARTY_ID/gdpr-portability-export"

/**
 * The customer channel must actually be able to exercise GDPR Art. 15 and Art. 20 (#8421).
 *
 * ## Why real HTTP
 *
 * A unit test that calls the resource class cannot tell a served route from an unserved one — this
 * repo has shipped an endpoint that 404'd on every request because a top-level function between
 * `@Path` and its class stole the annotation, while the unit test calling the class stayed green.
 * Before this change there was no route here at all: 136 `@Path` declarations, none for either
 * export, so both assertions below saw 404 with the body
 * `"Unable to find matching target resource method"`.
 *
 * ## Why registration is asserted by METHOD, not by status code
 *
 * The GET status is the UPSTREAM's, because `UpstreamClient` proxies it verbatim — an unstubbed
 * path answers 404 from `StubUpstreamResource`, and so does an unregistered route, from RESTEasy.
 * `/customer/v1/profile`, shipped long before this change, reads 404 for exactly that reason.
 * A POST discriminates the two without depending on any upstream: 405 means the path IS registered
 * and simply has no POST handler; 404 means it is not registered at all. The last test is the
 * known-negative that makes that reading valid — an undeclared sibling under the same prefix must
 * answer 404 to the same POST.
 *
 * ## What the stub assertions add
 *
 * That the edge calls the RIGHT party-service path, and that it derives the subject from the
 * customer's own token rather than anything the client sent: neither route takes a path or query
 * parameter, and the `X-Customer-Party-Id` party-service scopes by is the JWT's party.
 */
@QuarkusTest
@TestSecurity(user = "customer:$GDPR_PARTY_ID", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = GDPR_PARTY_ID)])
class GdprSelfServiceExportIT {

    @BeforeEach
    fun stubPartyService() {
        StubUpstreamResource.reset()
        StubUpstreamResource.stub(ART15_PATH, body = """{"party":{"id":"$GDPR_PARTY_ID"},"kyc":null}""")
        StubUpstreamResource.stub(ART20_PATH, body = """{"party":{"id":"$GDPR_PARTY_ID"},"accounts":[]}""")
    }

    @Test
    fun `the Art 15 export reaches party-service scoped to the caller's own party`() {
        Given { this } When { get("/customer/v1/privacy/gdpr-export") } Then {
            statusCode(200)
            body("party.id", equalTo(GDPR_PARTY_ID))
        }
        val calls = StubUpstreamResource.requests(ART15_PATH)
        assertThat(calls).hasSize(1)
        assertThat(partyHeaderOf(calls.single())).isEqualTo(GDPR_PARTY_ID)
    }

    @Test
    fun `the Art 20 portability export reaches its own party-service path, not the Art 15 one`() {
        Given { this } When { get("/customer/v1/privacy/portability-export") } Then {
            statusCode(200)
            body("party.id", equalTo(GDPR_PARTY_ID))
        }
        assertThat(StubUpstreamResource.requests(ART20_PATH)).hasSize(1)
        // The two rights have different scopes (ADR-0204 D1) and party-service audits them under
        // different article codes; a portability request that landed on the Art. 15 aggregation
        // would over-disclose legal-obligation data and log the wrong article.
        assertThat(StubUpstreamResource.requests(ART15_PATH)).isEmpty()
    }

    @Test
    fun `both routes are registered — a POST is a method mismatch, not an unknown path`() {
        Given { this } When { post("/customer/v1/privacy/gdpr-export") } Then { statusCode(405) }
        Given { this } When { post("/customer/v1/privacy/portability-export") } Then { statusCode(405) }
    }

    @Test
    fun `an undeclared sibling under the same prefix is still unknown (known-negative control)`() {
        Given { this } When { post("/customer/v1/privacy/no-such-export") } Then { statusCode(404) }
    }

    private fun partyHeaderOf(request: StubUpstreamResource.Companion.Request): String? {
        val entry = request.headers.entries.firstOrNull {
            it.key.equals("X-Customer-Party-Id", ignoreCase = true)
        }
        return entry?.value?.firstOrNull()
    }
}
