// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.rest

import com.openbank.swift.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "swift.*", ...)` annotations
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory) and
 * no OPA sidecar runs in the test profile, so these assert the interceptor is a
 * correct no-op in that state — not that a real policy decision is enforced (that is
 * the shared `AuthorizeInterceptor`'s own suite in openbank-libs-runtime, plus the
 * decision assertions in openbank-infra/gitops/components/payments/gen-swift-opa-bundle.sh).
 *
 * Every SwiftResource endpoint now also carries `@RolesAllowed` (2026-07-08, PR #568/#571)
 * — like sca/domestic-payment, that outer gate answers 401 before `@Authorize` ever runs.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class SwiftResourceAuthzTest {

    @Test
    fun `anonymous request is rejected by the RBAC outer gate, not the interceptor`() {
        // No identity at all: @RolesAllowed answers 401 BEFORE @Authorize runs — the
        // advisory interceptor must not turn that into a 403/500 of its own.
        Given { this } When {
            get("/api/v1/swift/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated read - request reaches the handler`() {
        // Unknown message id -> the handler's own not-found path answers, proving the
        // swift.read @Authorize check did not interfere with the call.
        Given { this } When {
            get("/api/v1/swift/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated list - request reaches the handler`() {
        // swift.list @Authorize check must not interfere with the call; an empty DB
        // still answers 200 with an empty array.
        Given { this } When {
            get("/api/v1/swift/messages")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated write - ack on unknown message still answers`() {
        // Unknown message id with a well-formed body -> SwiftService.acknowledge() throws
        // IllegalStateException ("SWIFT message not found"), mapped to 422 by
        // IllegalStateExceptionMapper (openbank-libs-runtime) — NOT a 404. This proves the
        // swift.acknowledge @Authorize check did not interfere with the call (a 403 or 500
        // here would mean the interceptor short-circuited or misbehaved).
        Given {
            contentType("application/json")
            body("""{"ackRef":"ACKREF-TEST-0001"}""")
        } When {
            post("/api/v1/swift/${UUID.randomUUID()}/ack")
        } Then {
            statusCode(422)
        }
    }
}
