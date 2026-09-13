// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.integration

import com.openbank.analytics.it.RedpandaTestResource
import com.openbank.libs.security.Roles
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The erasure endpoint over REAL HTTP (#8792 acceptance 4, #9671).
 *
 * #8792 asks that erasure be *"verified by an integration test, not by a comment"*. Before this
 * file **no test touched this surface at all** — `POST /api/v1/analytics/erasure` had never been
 * exercised, only `ErasureService` in isolation. Three things only a served request can establish,
 * and each is a defect this repo has already paid for once:
 *
 *  1. **The route is actually served.** `McpEndpoint` had a `@Path` that bound to a top-level
 *     function declared above the class, so RESTEasy never registered the resource and every
 *     `POST /mcp` answered 404 on a running pod — while the unit test that called the class
 *     directly stayed green (root `CLAUDE.md`). A unit test cannot tell a served route from an
 *     unserved one.
 *  2. **`erased` survives serialisation.** #9687 made it a DERIVED `val` (`outcome == ERASED`) so it
 *     can no longer disagree with `outcome`. That it still reaches the wire as a field was an
 *     assumption about Jackson's treatment of a Kotlin computed property, not a measurement —
 *     asserted here so a consumer reading `erased` cannot lose it silently.
 *  3. **Which `CryptoErasure` the build actually selected.** The vault adapter is
 *     `@IfBuildProperty(openbank.analytics.erasure.backend = "vault")`, resolved during
 *     augmentation, and the property is unset here as in the deployed image — so the honest answer
 *     is `NO_BACKEND`. If this ever returns `ERASED` without a real backend, #9671 has regressed.
 */
@QuarkusTest
@QuarkusTestResource(RedpandaTestResource::class)
class ErasureEndpointIT {

    private fun body(aggregateType: String, aggregateId: String) =
        """{"aggregateType":"$aggregateType","aggregateId":"$aggregateId"}"""

    /**
     * An erasable category (CONSENT) with no erasure backend in this build.
     *
     * 200 rather than 404 is half the assertion: it proves the resource is registered. The other
     * half is the payload, which must not claim a shred.
     */
    @Test
    @TestSecurity(user = "dpo", roles = [Roles.COMPLIANCE])
    fun `an erasable category with no backend answers NO_BACKEND over the wire`() {
        val json = (
            Given {
                contentType(ContentType.JSON)
                body(body("CONSENT", "11111111-1111-1111-1111-111111111111"))
            } When {
                post("/api/v1/analytics/erasure")
            } Then {
                statusCode(200)
            }
            ).extract().body().asString()

        assertThat(json).contains("\"outcome\":\"NO_BACKEND\"")
        // The derived getter must still be serialised — assumption 2 above, now measured.
        assertThat(json).contains("\"erased\":false")
        assertThat(json).contains("\"rowsAffected\":0")
        // The explanation is what a supervisor reads, so it must not assert a shred either.
        assertThat(json).doesNotContain("Crypto-shredded")
        assertThat(json).contains("No erasure backend")
    }

    /**
     * A statutory hold must stay distinguishable from a missing backend ON THE WIRE, not just in
     * the enum: one is a defensible legal position, the other a gap in the deployment. Folding them
     * together is the mistake #9687 exists to prevent.
     */
    @Test
    @TestSecurity(user = "dpo", roles = [Roles.COMPLIANCE])
    fun `a category under a statutory hold answers REFUSED_LEGAL_HOLD, not NO_BACKEND`() {
        val json = (
            Given {
                contentType(ContentType.JSON)
                body(body("TRANSACTION", "22222222-2222-2222-2222-222222222222"))
            } When {
                post("/api/v1/analytics/erasure")
            } Then {
                statusCode(200)
            }
            ).extract().body().asString()

        assertThat(json).contains("\"outcome\":\"REFUSED_LEGAL_HOLD\"")
        assertThat(json).contains("\"erased\":false")
        assertThat(json).doesNotContain("NO_BACKEND")
        assertThat(json).contains("Art. 17(3)(b)")
    }

    /** ADMIN is the other role the resource accepts; both paths must serve, not just the first. */
    @Test
    @TestSecurity(user = "ops", roles = [Roles.ADMIN])
    fun `the admin role reaches the endpoint too`() {
        Given {
            contentType(ContentType.JSON)
            body(body("CONSENT", "33333333-3333-3333-3333-333333333333"))
        } When {
            post("/api/v1/analytics/erasure")
        } Then {
            statusCode(200)
        }
    }

    /**
     * Without an accepted role the request must NOT be served. This is the negative control that
     * makes the three tests above mean something: if the endpoint answered everyone, "200 for
     * COMPLIANCE" would be evidence of nothing.
     */
    @Test
    @TestSecurity(user = "nobody", roles = ["ROLE_CUSTOMER"])
    fun `a role the resource does not accept is refused`() {
        Given {
            contentType(ContentType.JSON)
            body(body("CONSENT", "44444444-4444-4444-4444-444444444444"))
        } When {
            post("/api/v1/analytics/erasure")
        } Then {
            statusCode(403)
        }
    }
}
