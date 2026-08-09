// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.integration

import com.openbank.statement.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test

/**
 * A missing required `from`/`to` on a statement period handler must answer **400 naming the
 * parameter**, not 500 (#3624, follow-up to #3104).
 *
 * This has to be driven over REAL HTTP. The defect lives in JAX-RS parameter injection, so a unit
 * test that calls the handler supplies the very argument the framework does not — it passes against
 * the broken code and can never see the bug.
 *
 * Measured against the pre-fix signature (`from: String`, non-nullable): every one of the three
 * assertions below saw **500**. These handlers are plain `fun`, so Kotlin emits an
 * `Intrinsics.checkNotNullParameter` at bytecode offset 0 and the NPE lands before the first
 * statement of the body — which is also why writing `requireNotNull(from)` against the old
 * signature would have compiled to nothing. Only declaring the parameter nullable makes the guard
 * reachable at all.
 *
 * No `@DefaultValue` here on purpose. "Last month" is a tempting default for a statement range and
 * it is the wrong one: a period close assigns LEGAL SEQUENCES and is idempotent on
 * `(account, currency, period)`, so a guessed range does not fail loudly — it writes the *wrong*
 * statement. `format` on the same handlers genuinely is defaultable and keeps its `@DefaultValue`.
 *
 * The last test is the control. It exercises the same auth, the same profile and the same resource
 * over a handler with no `from`/`to`, and passes both before and after the fix on purpose — so the
 * redness of the first three is attributable to the guard and not to the harness.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(StatementMissingParamStatusIT.AuthzOffProfile::class)
@TestSecurity(user = "operator", roles = ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_VIEWER"])
class StatementMissingParamStatusIT {

    /**
     * `authz.enforce` defaults to true and there is no OPA sidecar under test, so the PDP fails
     * CLOSED and every `@Authorize` endpoint answers 503 before the handler is ever entered — which
     * would make this IT green about nothing. Turning enforcement off is what lets the request reach
     * the parameter binding this test is about. Literal values only: a profile loads in a different
     * classloader from the test class, so anything computed here is computed twice.
     */
    class AuthzOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    // `contentType` on the POSTs is required, not decoration: the resource is
    // `@Consumes(APPLICATION_JSON)`, so a POST without it is rejected with 415 before parameter
    // binding is reached and the test would be green about nothing.

    @Test
    fun `an absent from on the period close answers 400 naming the parameter`() {
        Given {
            contentType("application/json")
            queryParam("to", "2026-01-31")
        } When {
            post("/api/v1/statements/$ACCOUNT_ID/close")
        } Then {
            statusCode(400)
            body(containsString("'from'"))
        }
    }

    @Test
    fun `an absent to on the restate answers 400 naming the parameter`() {
        Given {
            contentType("application/json")
            queryParam("from", "2026-01-01")
        } When {
            post("/api/v1/statements/$ACCOUNT_ID/CZK/restate")
        } Then {
            statusCode(400)
            body(containsString("'to'"))
        }
    }

    @Test
    fun `an absent from and to on the ad-hoc export answers 400 naming the parameter`() {
        Given { this } When {
            get("/api/v1/statements/$ACCOUNT_ID/CZK/export")
        } Then {
            statusCode(400)
            body(containsString("'from'"))
        }
    }

    @Test
    fun `control - a handler with no from or to is unaffected and still answers 200`() {
        Given { this } When { get("/api/v1/statements/$ACCOUNT_ID") } Then { statusCode(200) }
    }

    private companion object {
        const val ACCOUNT_ID = "11111111-2222-3333-4444-555555555555"
    }
}
