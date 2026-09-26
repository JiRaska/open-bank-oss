// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.integration.FxBootSmokeIT
import com.openbank.fx.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "fx.*", ...)` annotations
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory) and
 * no OPA sidecar runs in the test profile, so these assert the interceptor is a
 * correct no-op in that state — not that a real policy decision is enforced (that is
 * the shared `AuthorizeInterceptor`'s own suite in openbank-libs-runtime, plus the
 * decision assertions in openbank-infra/gitops/components/fx-service/gen-fx-opa-bundle.sh).
 */
@QuarkusTest
@QuarkusTestResource(FxBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class FxResourceAuthzTest {

    @Test
    fun `anonymous request is rejected by the RBAC outer gate, not the interceptor`() {
        // No identity at all: @RolesAllowed answers 401 BEFORE @Authorize runs — the
        // advisory interceptor must not turn that into a 403/500 of its own.
        Given { this } When {
            get("/api/v1/fx/rates")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `advisory mode does not block an annotated list - request reaches the handler`() {
        // fx.list has no resource dependency, so a viewer sees a 200 (possibly an empty list) -
        // proving the @Authorize interceptor (advisory, no sidecar) did not short-circuit the call.
        Given { this } When {
            get("/api/v1/fx/rates")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `advisory mode does not block an annotated read - unknown pair still answers`() {
        // Unknown currency pair -> the handler's own not-found path answers, proving the
        // fx.read @Authorize check did not interfere with the call.
        Given { this } When {
            get("/api/v1/fx/rates/ZZZ/YYY")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated write - convert still reaches the use case`() {
        // No FX rate is seeded for this pair in the test DB, so the use case's own
        // "No FX rate available" failure answers (IllegalStateException -> 422 via the
        // shared exception mapper) - never a 401/403 from the interceptor itself, proving
        // the fx.convert @Authorize check did not interfere with the call. Content-Type is
        // required — JAX-RS answers 415 without it.
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """
                {
                  "partyId": "${UUID.randomUUID()}",
                  "partyName": "Authz Test Party",
                  "fromCurrency": "XXT",
                  "toCurrency": "YYT",
                  "fromAmountMinorUnits": 1000
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/fx/convert")
        } Then {
            statusCode(422)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `advisory mode does not block a resource-scoped read - unknown conversion still answers`() {
        // Unknown conversion id -> the handler's own not-found path answers, proving the
        // fx.read(resource = "#id") @Authorize check did not interfere with the call.
        Given { this } When {
            get("/api/v1/fx/conversions/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }
}
