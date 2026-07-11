// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.sca.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "scaChallenge.*", ...)` additions
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory) and
 * no OPA sidecar runs in the test profile, so these assert the interceptor is a
 * correct no-op in that state — not that a real policy decision is enforced (that is
 * the shared `AuthorizeInterceptor`'s own suite in openbank-libs-runtime, plus the
 * decision assertions in openbank-infra/gitops/components/sca/gen-sca-opa-bundle.sh).
 *
 * `get`/`verify` now carry a class-appropriate `@RolesAllowed` pairing (the coarse gate
 * `@Authorize` was always meant to sit behind, per libs-domain's own docs) — an anonymous
 * request is rejected before it ever reaches the `@Authorize` interceptor.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaResourceAuthzTest {

    @Test
    fun `anonymous request is rejected before it reaches the Authorize interceptor`() {
        // Previously this reached the handler (404) — get() had @Authorize but no
        // @RolesAllowed pairing, so JAX-RS never rejected an unauthenticated caller.
        Given { this } When {
            get("/api/v1/sca/challenges/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated read - request reaches the handler`() {
        // Unknown challenge id -> the handler's own not-found path answers, proving the
        // @Authorize interceptor (advisory, no sidecar) did not short-circuit the call.
        Given { this } When {
            get("/api/v1/sca/challenges/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated write - verify still answers`() {
        // Unknown challenge id with a well-formed body -> ScaChallengeNotFoundException 404,
        // proving the scaChallenge.verify @Authorize check did not interfere with the call.
        Given {
            contentType("application/json")
            body("""{"partyId":"${UUID.randomUUID()}","otp":"123456"}""")
        } When {
            post("/api/v1/sca/challenges/${UUID.randomUUID()}/verify")
        } Then {
            statusCode(404)
        }
    }
}
