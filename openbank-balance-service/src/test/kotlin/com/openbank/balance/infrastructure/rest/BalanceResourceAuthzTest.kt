// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the `@Authorize(action = "balance.*", ...)` additions (ADR-0034 Phase 5,
 * issue #266). `authz.enforce` defaults to `false` (advisory) and no OPA sidecar runs in the test
 * profile, so these assert the interceptor is a correct no-op in that state — not that a real
 * policy decision is enforced (that is the shared `AuthorizeInterceptor`'s own suite in
 * openbank-libs-runtime, plus the decision assertions in
 * openbank-infra/gitops/components/balances/gen-balance-opa-bundle.sh).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class BalanceResourceAuthzTest {

    @Test
    fun `anonymous request is rejected by the RBAC outer gate, not the interceptor`() {
        // No identity at all: @RolesAllowed answers 401 BEFORE @Authorize runs.
        Given { this } When {
            get("/api/v1/balances/${UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block an annotated read - unknown account returns an empty list`() {
        // getBalances never 404s (empty list for an unknown account) — proves the balance.read
        // @Authorize check did not short-circuit the call in advisory mode.
        Given { this } When {
            get("/api/v1/balances/${UUID.randomUUID()}")
        } Then {
            statusCode(200)
            body("balances.size()", equalTo(0))
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block a single-currency read - unknown account 404s from the handler`() {
        // Unknown account+currency -> BalanceNotFoundException -> 404, proving the balance.read
        // check on getBalance did not interfere (a blocked call would never reach the use case).
        Given { this } When {
            get("/api/v1/balances/${UUID.randomUUID()}/CZK")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block credit - unknown account 404s from the handler, not the interceptor`() {
        // Content-Type is required -- JAX-RS answers 415 without it -- then BalanceNotFoundException
        // 404s, proving the balance.credit @Authorize check did not interfere.
        Given {
            contentType("application/json")
            body("""{"amount":10.00,"currency":"CZK","referenceId":"ref-credit-1"}""")
        } When {
            post("/api/v1/balances/${UUID.randomUUID()}/credit")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block debit - unknown account 404s from the handler, not the interceptor`() {
        Given {
            contentType("application/json")
            body("""{"amount":10.00,"currency":"CZK","referenceId":"ref-debit-1"}""")
        } When {
            post("/api/v1/balances/${UUID.randomUUID()}/debit")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block placeHold - unknown account 404s from the handler, not the interceptor`() {
        Given {
            contentType("application/json")
            body("""{"amount":10.00,"currency":"CZK","reason":"test-hold","referenceId":"ref-hold-1"}""")
        } When {
            post("/api/v1/balances/${UUID.randomUUID()}/holds")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block releaseHold - unknown hold 404s from the handler, not the interceptor`() {
        // releaseHold's @Authorize resource expression is #holdId (not #accountId) -- exercising
        // this path with an unrelated random id verifies the resource-parameter binding resolves.
        Given { this } When {
            delete("/api/v1/balances/holds/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_SUPERVISOR"])
    fun `advisory mode does not block setOverdraftLimit - unknown account 404s from the handler`() {
        Given {
            contentType("application/json")
            body("""{"arrangedOverdraftLimit":500.00}""")
        } When {
            put("/api/v1/balances/${UUID.randomUUID()}/CZK/overdraft-limit")
        } Then {
            statusCode(404)
        }
    }

    // Regression coverage for the `balance.approval.decide` money-path authz gap found by PR
    // #5686's OPA verification: the action was missing from rules.yaml's role_action_matrix, so
    // real OPA evaluation resolved allow=false for every OPERATOR and the four-eyes checker
    // endpoint 403'd in any AUTHZ_ENFORCE=true environment. This test profile never runs a real
    // OPA sidecar (authz.enforce defaults to false here, same as every other test above) so it
    // cannot itself prove the grant — that is what
    // openbank-infra/gitops/components/balances/balance_rest_ext_test.rego's
    // test_operator_may_decide_approval / test_edge_may_not_decide_approval_despite_matrix_grant
    // assert against the real regenerated bundle. What this test DOES prove: the interceptor is
    // a correct no-op for balance.approval.decide in advisory mode, matching every other
    // endpoint in this class, so the endpoint's own behavior (unknown approval id -> 404) is
    // reachable and not masked by the authz layer.
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block approval decide - unknown approval id 404s from the handler`() {
        Given {
            contentType("application/json")
            body("""{"approve":true}""")
        } When {
            patch("/api/v1/balances/approvals/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }
}
