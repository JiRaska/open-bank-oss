// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.integration.DomesticPaymentBootSmokeIT
import com.openbank.domestic.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "domestic-payment.*", ...)` additions
 * (ADR-0034 Phase 5, issue #266). `authz.enforce` defaults to `false` (advisory) and
 * no OPA sidecar runs in the test profile, so these assert the interceptor is a
 * correct no-op in that state — not that a real policy decision is enforced (that is
 * the shared `AuthorizeInterceptor`'s own suite in openbank-libs-runtime, plus the
 * decision assertions in
 * openbank-infra/gitops/components/payments/gen-domestic-payment-opa-bundle.sh).
 */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DomesticPaymentResourceAuthzTest {

    @Test
    fun `anonymous request is rejected by the RBAC outer gate, not the interceptor`() {
        // No identity at all: @RolesAllowed answers 401 BEFORE @Authorize runs — the
        // advisory interceptor must not turn that into a 403/500 of its own. (Unlike
        // sca, every DomesticPaymentResource endpoint carries @RolesAllowed.)
        Given { this } When {
            get("/api/v1/domestic-payments/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated read - request reaches the handler`() {
        // Unknown payment id -> the handler's own not-found path answers, proving the
        // @Authorize interceptor (advisory, no sidecar) did not short-circuit the call.
        Given { this } When {
            get("/api/v1/domestic-payments/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated write - transitionStatus still answers`() {
        // Unknown payment id with a well-formed body (Content-Type is required — JAX-RS
        // answers 415 without it) -> DomesticPaymentNotFoundException 404, proving the
        // domestic-payment.transitionStatus @Authorize check did not interfere.
        Given {
            contentType("application/json")
            body("""{"targetStatus":"CANCELLED"}""")
        } When {
            patch("/api/v1/domestic-payments/${UUID.randomUUID()}/status")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
    fun `trusted edge reaches delegated route and gets a retryable projection response`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "delegated-route-1")
            header("X-Customer-Party-Id", UUID.randomUUID().toString())
            header("X-Delegation-Id", UUID.randomUUID().toString())
            header("X-Delegation-Reservation-Id", UUID.randomUUID().toString())
            body(validPaymentRequest())
        } When {
            post("/api/v1/domestic-payments/delegated")
        } Then {
            statusCode(425)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `non edge workload identity cannot use delegated route`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "delegated-route-2")
            header("X-Customer-Party-Id", UUID.randomUUID().toString())
            header("X-Delegation-Id", UUID.randomUUID().toString())
            header("X-Delegation-Reservation-Id", UUID.randomUUID().toString())
            body(validPaymentRequest())
        } When {
            post("/api/v1/domestic-payments/delegated")
        } Then {
            statusCode(400)
        }
    }

    private fun validPaymentRequest(): String = """
        {"debtorAccountId":"${UUID.randomUUID()}","debtorAccountNumber":"1234567890","debtorBankCode":"0800",
        "debtorName":"Grantor","creditorAccountNumber":"0987654321","creditorBankCode":"0100",
        "creditorName":"Payee","amount":1500.00,"currency":"CZK","priority":"STANDARD",
        "statementLabel":null,"endToEndId":"delegated-route-test"}
    """.trimIndent()
}
