// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the pre-existing `@Authorize(action = "transaction.*", ...)`
 * annotations (ADR-0034 Phase 5, issue #266) now that the OPA sidecar + bundle are wired
 * up. `authz.enforce` defaults to `false` (advisory) and no OPA sidecar runs in the test
 * profile, so these assert the interceptor is a correct no-op in that state — not that a
 * real policy decision is enforced (that is the shared `AuthorizeInterceptor`'s own suite
 * in openbank-libs-runtime, plus the decision assertions run against the composed policy
 * via `opa eval`, see openbank-infra/gitops/components/payments/gen-transaction-opa-bundle.sh
 * and the PR description for the exact assertions).
 *
 * transaction-service's `@Authorize` annotations were already present on all five
 * endpoints (list/search/read/create/reverse) before this change — this test is new
 * coverage for the OPA-enforce flip (sidecar + AUTHZ_ENFORCE=true), not a change to the
 * annotations themselves.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class TransactionResourceAuthzTest {

    @Test
    fun `anonymous request is rejected by the RBAC outer gate, not the interceptor`() {
        // No identity at all: @RolesAllowed answers 401 BEFORE @Authorize runs — the
        // advisory interceptor must not turn that into a 403/500 of its own.
        Given { this } When {
            get("/api/v1/transactions/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated read - unknown id reaches the handler`() {
        // Unknown transaction id -> the handler's own not-found path answers, proving the
        // @Authorize interceptor (advisory, no sidecar) did not short-circuit the call.
        Given { this } When {
            get("/api/v1/transactions/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block search - request reaches the handler`() {
        Given { this } When {
            get("/api/v1/transactions/search?accountId=${UUID.randomUUID()}")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block reverse - well-formed request reaches the handler`() {
        // Content-Type is required — JAX-RS answers 415 without it, which would be a false
        // positive for "the interceptor blocked it". A well-formed body against an unknown
        // transaction id resolves to the handler's own not-found path — reverseTransaction
        // maps "not found" to 400 VALIDATION_ERROR (unlike getTransaction, which maps it to
        // 404; pre-existing handler behavior, unrelated to this OPA change) — proving the
        // transaction.reverse @Authorize check did not interfere.
        Given {
            contentType("application/json")
            body("""{"idempotencyKey":"${UUID.randomUUID()}","reason":"test"}""")
        } When {
            post("/api/v1/transactions/${UUID.randomUUID()}/reverse")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST reverse without Content-Type is rejected by JAX-RS before the handler runs`() {
        // No Content-Type on a JSON POST endpoint -> JAX-RS answers 415 itself, before the
        // @Authorize interceptor or the handler ever run. Confirms the advisory interceptor
        // introduces no new failure mode ahead of that standard behavior.
        Given { this } When {
            post("/api/v1/transactions/${UUID.randomUUID()}/reverse")
        } Then {
            statusCode(415)
        }
    }
}
