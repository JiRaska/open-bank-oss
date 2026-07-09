// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "lending.*", ...)` gates under
 * enforcement rollout (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to
 * `false` (advisory) and no OPA sidecar runs in the test profile, so these assert the
 * interceptor is a correct no-op in that state — not that a real policy decision is
 * enforced (that is the shared `AuthorizeInterceptor`'s own suite in
 * openbank-libs-runtime, plus the decision assertions validated against the composed
 * bundle in openbank-infra/gitops/components/lending/gen-lending-opa-bundle.sh).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class LendingResourceAuthzTest {

    @Test
    fun `anonymous request is rejected by RBAC - not by the authz interceptor`() {
        // Every LendingResource endpoint is @RolesAllowed, so an anonymous request is
        // 401-rejected by the RBAC outer gate BEFORE @Authorize runs. Advisory mode must
        // not change that contract (a 403/500 here would mean the interceptor fired on an
        // unauthenticated request).
        Given { this } When {
            get("/api/v1/lending/applications/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_LENDING_OFFICER"])
    fun `advisory mode does not block an annotated read - request reaches the handler`() {
        // Unknown loan id -> the handler's own not-found path answers, proving the
        // @Authorize interceptor (advisory, no sidecar) did not short-circuit the call.
        Given { this } When {
            get("/api/v1/lending/loans/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_CREDIT_RISK"])
    fun `advisory mode does not block an annotated write - decision still answers`() {
        // Unknown application id with a well-formed body -> the use case fails and the
        // handler's recoverWithItem maps it to 409, proving the lending.approve
        // @Authorize check did not interfere with the call.
        Given {
            contentType("application/json")
            body("""{"approve":false,"reason":"authz advisory-mode regression"}""")
        } When {
            post("/api/v1/lending/applications/${UUID.randomUUID()}/decision")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_LENDING_OFFICER"])
    fun `advisory mode does not block collateral registration - request reaches the handler`() {
        // Unknown loan id with a well-formed body -> the FK constraint on collateral.loan_id fails
        // the save and the handler's recoverWithItem maps it to 400, proving the
        // lending.collateralRegister @Authorize check (ADR-0028 follow-up, issue #621) did not
        // interfere with the call in advisory mode (a 403/500 here would mean it did).
        Given {
            contentType("application/json")
            body(
                """{"type":"VEHICLE","marketValue":{"amount":5000.00,"currency":"EUR"},"haircut":0.40}""",
            )
        } When {
            post("/api/v1/lending/loans/${UUID.randomUUID()}/collateral")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_CREDIT_RISK"])
    fun `advisory mode does not block a collateral decision - request reaches the handler`() {
        // Unknown collateral id -> the use case fails and the handler's recoverWithItem maps
        // it to 409, proving the lending.collateralDecide @Authorize check did not interfere.
        Given {
            contentType("application/json")
            body("""{"approve":true}""")
        } When {
            post("/api/v1/lending/collateral/${UUID.randomUUID()}/decision")
        } Then {
            statusCode(409)
        }
    }
}
