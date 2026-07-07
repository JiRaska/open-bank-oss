// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.rest

import com.openbank.sepainstant.it.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "sctInstPayment.*", ...)` additions
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory) and no
 * OPA sidecar runs in the test profile, so these assert the interceptor is a correct
 * no-op in that state — not that a real policy decision is enforced (that is the shared
 * `AuthorizeInterceptor`'s own suite in openbank-libs-runtime, plus the decision
 * assertions run against the composed bundle from
 * openbank-infra/gitops/components/payments/gen-sepa-instant-opa-bundle.sh).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
class SctInstResourceAuthzTest {

    @Test
    fun `anonymous request is stopped by the RBAC outer gate - not the advisory interceptor`() {
        // Every SctInstResource endpoint carries @RolesAllowed, so an anonymous request is
        // rejected 401 by the RBAC outer gate BEFORE @Authorize runs. The assertion pins that
        // ordering: adding @Authorize must not turn the 401 into a 403/500.
        Given { this } When {
            get("/api/v1/sepa-instant/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated read - request reaches the handler`() {
        // Unknown payment id -> SctInstPaymentNotFoundException 404, proving the
        // sctInstPayment.read @Authorize check (advisory, no sidecar) did not short-circuit.
        Given { this } When {
            get("/api/v1/sepa-instant/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated list - request reaches the handler`() {
        Given { this } When {
            get("/api/v1/sepa-instant")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated list-by-debtor - request reaches the handler`() {
        Given { this } When {
            get("/api/v1/sepa-instant/debtor/${UUID.randomUUID()}")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated write - submit requires Content-Type`() {
        // No Content-Type header on a POST with a body -> Quarkus RESTEasy Reactive answers
        // 415 before the handler (and before @Authorize) ever runs. Pins that @Authorize
        // does not change this pre-existing content-negotiation behaviour.
        Given {
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """{ "debtorAccountId": "${UUID.randomUUID()}", "debtorIban": "CZ00", """ +
                    """"debtorName": "A", "creditorIban": "DE00", "creditorName": "B", """ +
                    """"creditorBic": "COBADEFFXXX", "amount": 1, "currency": "EUR" }""",
            )
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(415)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated recall - request reaches the handler`() {
        // Unknown payment id -> SctInstPaymentNotFoundException 404, proving the pre-existing
        // sctInstPayment.recall @Authorize check still doesn't interfere in advisory mode.
        Given {
            contentType("application/json")
            body("""{"reason":"authz-advisory-regression"}""")
        } When {
            post("/api/v1/sepa-instant/${UUID.randomUUID()}/recall")
        } Then {
            statusCode(404)
        }
    }
}
