// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.it.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "account.*", ...)` annotations
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory) and no
 * OPA sidecar runs in the test profile, so these assert the interceptor is a correct
 * no-op in that state — not that a real policy decision is enforced (that is the shared
 * `AuthorizeInterceptor`'s own suite in openbank-libs-runtime, plus the decision
 * assertions in openbank-infra/gitops/components/accounts/gen-account-opa-bundle.sh,
 * the billing #179 / sca #266 pattern).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
class AccountResourceAuthzTest {

    @Test
    fun `anonymous request still reaches the handler in advisory mode`() {
        // The test profile runs without enforced OIDC, so the request carries no identity
        // at all — the strictest input the interceptor can see. Advisory mode must not
        // 401/403/500 it before RBAC even runs; @RolesAllowed answers first (401, no
        // identity), proving the @Authorize interceptor did not short-circuit ahead of it.
        Given { this } When {
            get("/api/v1/accounts/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    fun `anonymous request to the IBAN lookup answers 401`() {
        // The VoP hop-1 path (GET /api/v1/accounts/iban/{iban}, #8552). A recorded 401 pact
        // cannot survive provider replay — the replay TestAuthMechanism authenticates every
        // request as pact-verifier/ROLE_OPERATOR — so the negative case lives HERE, where an
        // anonymous request genuinely carries no identity.
        Given { this } When {
            get("/api/v1/accounts/iban/CZ6508000000192000145399")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated read - request reaches the handler`() {
        // Unknown account id -> the handler's own not-found path answers, proving the
        // account.read @Authorize check (advisory, no sidecar) did not interfere.
        Given { this } When {
            get("/api/v1/accounts/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block the four-eyes-flagged freeze action - request reaches the handler`() {
        // account.freeze is the money-path four-eyes verb (rules.yaml four_eyes.verbs).
        // Unknown account id + well-formed body -> AccountNotFoundException 404, proving
        // the @Authorize interceptor did not short-circuit the call in advisory mode.
        Given {
            contentType("application/json")
            body("""{"reason":"suspected fraud"}""")
        } When {
            post("/api/v1/accounts/${UUID.randomUUID()}/freeze")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block account close - request reaches the handler`() {
        Given {
            contentType("application/json")
            body("""{"reason":"customer request"}""")
        } When {
            post("/api/v1/accounts/${UUID.randomUUID()}/close")
        } Then {
            statusCode(404)
        }
    }
}
