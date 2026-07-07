// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.rest

import com.openbank.settlement.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the `@Authorize(action = "settlement.create", ...)` addition
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory) and no
 * OPA sidecar runs in the test profile, so these assert the interceptor is a correct
 * no-op in that state — not that a real policy decision is enforced (that is the shared
 * `AuthorizeInterceptor`'s own suite in openbank-libs-runtime, plus the decision
 * assertions in
 * openbank-infra/gitops/components/payments/gen-settlement-opa-bundle.sh).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SettlementResourceAuthzTest {

    @Test
    fun `anonymous request is rejected by the RBAC outer gate, not the interceptor`() {
        // No identity at all: @RolesAllowed answers 401 BEFORE @Authorize runs — the
        // advisory interceptor must not turn that into a 403/500 of its own.
        Given {
            contentType("application/json")
            body(VALID_REQUEST)
        } When {
            post("/api/v1/settlements")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated write - well-formed request reaches the handler`() {
        // Content-Type is required — JAX-RS answers 415 without it. A well-formed body with
        // a real operator identity proves the settlement.create @Authorize check did not
        // interfere: the request reaches the use case and gets a real 201.
        Given {
            contentType("application/json")
            body(VALID_REQUEST)
        } When {
            post("/api/v1/settlements")
        } Then {
            statusCode(201)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `missing Content-Type is rejected before validation or authorization`() {
        Given {
            body(VALID_REQUEST)
        } When {
            post("/api/v1/settlements")
        } Then {
            statusCode(415)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `a role outside RolesAllowed is rejected by RBAC before the interceptor runs`() {
        // ROLE_VIEWER is not in @RolesAllowed(SERVICE, OPERATOR, ADMIN) on this endpoint —
        // the coarse gate answers 403 without ever consulting the (advisory) OPA decision.
        Given {
            contentType("application/json")
            body(VALID_REQUEST)
        } When {
            post("/api/v1/settlements")
        } Then {
            statusCode(403)
        }
    }

    private companion object {
        const val VALID_REQUEST = """
            {
              "idempotencyKey": "authz-test-key-1",
              "payerAccountId": "a0000000-0000-0000-0000-000000000010",
              "payeeAccountId": "a0000000-0000-0000-0000-000000000011",
              "amount": "10.00",
              "currency": "CZK"
            }
        """
    }
}
