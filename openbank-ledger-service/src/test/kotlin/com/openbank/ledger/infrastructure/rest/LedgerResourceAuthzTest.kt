// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for wiring a real [com.openbank.libs.authz.PolicyDecisionPoint] bean
 * (ADR-0034 Phase 5, issue #266) into ledger-service, the core money-path double-entry ledger.
 * `authz.enforce` defaults to `false` (advisory) and no OPA sidecar runs in the test profile, so
 * these assert the interceptor + a real [com.openbank.ledger.infrastructure.authz.AuthzProducer]
 * bean are a correct no-op in that state (the OPA sidecar call fails — connection refused — and
 * advisory mode logs + proceeds) — not that a real policy decision is enforced. Real decisions are
 * asserted by `opa eval` against the composed bundle in
 * `openbank-infra/gitops/components/ledger/gen-ledger-opa-bundle.sh`, plus the shared
 * `AuthorizeInterceptor` suite in openbank-libs-runtime (the billing #179 / sca / domestic-payment
 * pattern).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class LedgerResourceAuthzTest {

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `advisory mode does not block an annotated read - unknown journal answers 404`() {
        // ledger.read on an unknown id -> the handler's own not-found path answers, proving the
        // @Authorize interceptor (advisory, PDP unreachable in test) did not short-circuit the call.
        Given { this } When {
            get("/api/v1/journals/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block ledger create - malformed body reaches deserialization, not authz`() {
        // A deliberately incomplete body fails Jackson deserialization (400/422), never the
        // @Authorize interceptor's own 403 (deny) or 503 (PDP unreachable, enforce=true) — proving
        // ledger.create is a correct no-op in advisory mode.
        val status = (
            Given {
                contentType("application/json")
                body("""{}""")
            } When {
                post("/api/v1/journals")
            }
            ).statusCode()
        org.assertj.core.api.Assertions.assertThat(status).isNotIn(403, 503)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block ledger reverse - unknown journal answers 404`() {
        Given {
            contentType("application/json")
            body("""{"reason":"test","reversedBy":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/journals/${UUID.randomUUID()}/reverse")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block ledger trigger - fx revaluation call reaches the use case`() {
        // Whatever the FX revaluation use case's own verdict is (it depends on a CNB rate
        // provider), it must not be the @Authorize interceptor's own 403 (deny) or 503 (PDP
        // unreachable, enforce=true) — this is the ledger.trigger no-op assertion.
        val status = (Given { this } When { post("/api/v1/ledger/fx-revaluation") }).statusCode()
        org.assertj.core.api.Assertions.assertThat(status).isNotIn(403, 503)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `advisory mode does not block year-close create draft`() {
        // Whatever the use case's own business-rule verdict is for a fiscal year with no seeded
        // activity, it must not be the @Authorize interceptor's own 403 (deny) or 503 (PDP
        // unreachable, enforce=true) — this is the ledger.close.draft no-op assertion,
        // deliberately distinct from LedgerResource's M2M-capable ledger.create action above.
        val status = (Given { this } When { post("/api/v1/ledger/close/1999") }).statusCode()
        org.assertj.core.api.Assertions.assertThat(status).isNotIn(403, 503)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_AUDITOR"])
    fun `advisory mode does not block control-account tie-out read`() {
        // ControlAccountResource's @RolesAllowed admits SERVICE/AUDITOR/OPERATOR/ADMIN — NOT
        // VIEWER (unlike LedgerResource/YearCloseResource) — so ROLE_AUDITOR is the correct
        // RBAC-permitted role to exercise the ledger.read @Authorize no-op here.
        //
        // Tolerant assertion (not a fixed 200): PanacheJournalRepository's controlAccountTieOut
        // native query references `jl.account_id`, but the real journal_lines column is
        // `gl_account_id` (V1__init_ledger.sql) — a pre-existing SQL defect, unrelated to authz,
        // that this is apparently the first test to ever exercise end-to-end (500 today).
        // Asserting "not 403/503" keeps this test's purpose (authz no-op) independent of that
        // unrelated bug; see PR "Residual risk" — not fixed here (out of scope for a
        // security-only ledger PR).
        val status = (
            Given { this } When { get("/api/v1/control-accounts/${UUID.randomUUID()}/tie-out") }
            ).statusCode()
        org.assertj.core.api.Assertions.assertThat(status).isNotIn(403, 503)
    }
}
