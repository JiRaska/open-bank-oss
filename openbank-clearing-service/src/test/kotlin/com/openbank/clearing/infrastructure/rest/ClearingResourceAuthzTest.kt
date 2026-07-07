// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.rest

import com.openbank.clearing.it.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "clearingBatch.*", ...)` additions
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory) and no OPA
 * sidecar is deployed in the test profile, so these assert the interceptor is a correct
 * no-op in that state — not that a real policy decision is enforced (that is the shared
 * `AuthorizeInterceptor`'s own test suite in openbank-libs-runtime, plus the `opa eval`
 * decision assertions run against the composed policy for the PR — see the PR description).
 * Real Postgres/Kafka/Redis via Testcontainers (the same resource ClearingApiAccessControlIT
 * uses) — the endpoints under test are DB-backed, not pure in-memory.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
class ClearingResourceAuthzTest {

    @Test
    fun `unauthenticated request is rejected before authorization ever runs`() {
        Given { this } When {
            get("/api/v1/clearing/batches")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `submit without a Content-Type header is rejected before authorization ever runs`() {
        // ClearingResource is class-level @Consumes(APPLICATION_JSON); JAX-RS answers 415
        // before the method body (and thus the @Authorize interceptor) is ever invoked —
        // proves the interceptor doesn't mask or reorder this pre-existing behaviour.
        Given {
            body("""{"paymentId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(415)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `authenticated operator reaches the batch list in advisory mode`() {
        // No OPA sidecar in the test profile and authz.enforce defaults false — the
        // interceptor must be a no-op here, so RBAC alone (@RolesAllowed ROLE_OPERATOR)
        // decides the outcome.
        Given { this } When {
            get("/api/v1/clearing/batches")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `authenticated viewer reaches batch items in advisory mode`() {
        // getBatchItems now carries @Authorize(action = "clearingBatch.readItems", ...);
        // advisory mode must not change the pre-existing RBAC-only outcome (200, not 404,
        // since the use case returns an empty list for an unknown batch id rather than
        // failing — this only asserts the authz interceptor is a no-op, not use-case behavior).
        Given { this } When {
            get("/api/v1/clearing/batches/${UUID.randomUUID()}/items")
        } Then {
            statusCode(200)
        }
    }
}
