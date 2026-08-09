// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.dispute.integration

import com.openbank.dispute.it.PostgresTestResource
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
 * A missing required `actor` on withdraw/escalate must answer **400 naming the parameter**, not 500
 * (#3624, follow-up to #3104).
 *
 * This has to be driven over REAL HTTP. The defect lives in JAX-RS parameter injection, so a unit
 * test that calls the handler supplies the very argument the framework does not — it passes against
 * the broken code and can never see the bug.
 *
 * Measured against the pre-fix signature (`actor: String`, non-nullable): both assertions saw
 * **500**. These handlers are plain `fun`, so Kotlin emits an `Intrinsics.checkNotNullParameter` at
 * bytecode offset 0 and the NPE lands before the first statement of the body — which is also why
 * writing `requireNotNull(actor)` against the old signature would have compiled to nothing.
 *
 * `actor` is written to the dispute timeline as attribution for who withdrew or escalated the case,
 * so it is genuinely required — defaulting it would file an unattributable state change.
 *
 * The last test is the control: the same auth, the same profile and the same resource over a
 * handler that takes no `actor`. It passes both before and after the fix on purpose, so the
 * redness of the first two is attributable to the guard and not to the harness.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DisputeMissingParamStatusIT.AuthzOffProfile::class)
@TestSecurity(user = "operator", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
class DisputeMissingParamStatusIT {

    /**
     * `authz.enforce` defaults to true and there is no OPA sidecar under test, so the PDP fails
     * CLOSED and every `@Authorize` endpoint answers 503 before the handler is ever entered — which
     * would make this IT green about nothing. Literal values only: a profile loads in a different
     * classloader from the test class, so anything computed here is computed twice.
     */
    class AuthzOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    // `contentType` is required, not decoration: the resource is `@Consumes(APPLICATION_JSON)`, so a
    // POST without it is rejected with 415 before parameter binding is reached and the test would
    // be green about nothing (measured — the first run of this file saw 415, not the defect).

    @Test
    fun `an absent actor on withdraw answers 400 naming the parameter`() {
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/disputes/$DISPUTE_ID/withdraw")
        } Then {
            statusCode(400)
            body(containsString("'actor'"))
        }
    }

    @Test
    fun `an absent actor on escalate answers 400 naming the parameter`() {
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/disputes/$DISPUTE_ID/escalate")
        } Then {
            statusCode(400)
            body(containsString("'actor'"))
        }
    }

    @Test
    fun `control - a handler that takes no actor is unaffected and still answers 200`() {
        Given { this } When { get("/api/v1/disputes/account/$ACCOUNT_ID") } Then { statusCode(200) }
    }

    private companion object {
        const val DISPUTE_ID = "11111111-2222-3333-4444-555555555555"
        const val ACCOUNT_ID = "66666666-7777-8888-9999-aaaaaaaaaaaa"
    }
}
