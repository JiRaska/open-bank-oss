// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest

import com.openbank.sepa.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "sepaPayment.*", ...)` additions
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory) and
 * no OPA sidecar runs in the test profile, so these assert the interceptor is a
 * correct no-op in that state — not that a real policy decision is enforced (that is
 * the shared `AuthorizeInterceptor`'s own suite in openbank-libs-runtime, plus the
 * decision assertions in openbank-infra/gitops/components/payments/gen-sepa-payment-opa-bundle.sh).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class SepaPaymentResourceAuthzTest {

    @Test
    fun `anonymous request is stopped by the RBAC outer gate - not the advisory interceptor`() {
        // Every SepaPaymentResource endpoint carries @RolesAllowed, so an anonymous request
        // is rejected 401 by the RBAC outer gate BEFORE @Authorize runs (unlike sca-service,
        // whose reads have no @RolesAllowed and fall through to the handler). The assertion
        // pins that ordering: adding @Authorize must not turn the 401 into a 403/500.
        Given { this } When {
            get("/api/v1/sepa-payments/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated read - request reaches the handler`() {
        // Unknown payment id -> SepaPaymentNotFoundException 404, proving the
        // sepaPayment.read @Authorize check (advisory, no sidecar) did not short-circuit.
        Given { this } When {
            get("/api/v1/sepa-payments/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated list - request reaches the handler`() {
        Given { this } When {
            get("/api/v1/sepa-payments")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated write - status transition still answers`() {
        // Unknown payment id with a well-formed body -> SepaPaymentNotFoundException 404,
        // proving the sepaPayment.transitionStatus @Authorize check did not interfere.
        Given {
            contentType("application/json")
            body("""{"targetStatus":"PROCESSING","reason":"authz-advisory-regression"}""")
        } When {
            patch("/api/v1/sepa-payments/${UUID.randomUUID()}/status")
        } Then {
            statusCode(404)
        }
    }
}
