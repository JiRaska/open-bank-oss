// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.rest

import com.openbank.fraud.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the `@Authorize(action = "fraud.score")` addition
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory)
 * and no OPA sidecar runs in the test profile, so these assert the interceptor
 * is a correct no-op in that state — not that a real policy decision is enforced
 * (that is the shared `AuthorizeInterceptor`'s own suite in openbank-libs-runtime,
 * plus the decision assertions run against the composed bundle from
 * openbank-infra/gitops/components/fraud-service/gen-fraud-opa-bundle.sh).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class FraudResourceAuthzTest {

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated write for an operator`() {
        // A valid scoring request reaches the handler and returns the baseline verdict,
        // proving the @Authorize interceptor (advisory, no sidecar) did not short-circuit
        // the call with a 403/500.
        Given {
            contentType("application/json")
            body("""{"amount":"10.00","currency":"CZK","rail":"SEPA"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "fx-service", roles = ["ROLE_API"])
    fun `advisory mode does not block the M2M scoring path`() {
        // The fx-service shadow-scoring shape (FraudScoreClient) — the exact M2M call the
        // service-fraud-scoring ext-policy rule grants once AUTHZ_ENFORCE flips.
        Given {
            contentType("application/json")
            body("""{"amount":"1250.00","currency":"CZK","rail":"FX"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not interfere with request validation`() {
        // Malformed body -> the deserialization/validation path answers 400, proving the
        // fraud.score @Authorize check did not swallow or alter handler-side validation.
        Given {
            contentType("application/json")
            body("""{"currency":"CZK"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(400)
        }
    }

    @Test
    fun `anonymous request is still rejected by the outer RolesAllowed gate`() {
        // The test profile disables OIDC but @RolesAllowed still applies: no identity ->
        // 401 from the RBAC outer gate, unchanged by the @Authorize addition.
        Given {
            contentType("application/json")
            body("""{"amount":"10.00","currency":"CZK","rail":"SEPA"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(401)
        }
    }
}
