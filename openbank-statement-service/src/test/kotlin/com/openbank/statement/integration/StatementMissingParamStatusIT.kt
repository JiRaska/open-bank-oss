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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An omitted `from`/`to` on an ad-hoc export must answer 400, not 500 (#3624).
 *
 * This is the OTHER half of the mechanism from [Psd2MissingHeaderStatusIT]'s suspend handlers, and
 * the unambiguous one: `export` is a plain `fun`, so Kotlin emits `Intrinsics.checkNotNullParameter`
 * at bytecode offset 0. With the old non-nullable signature the NPE was thrown before the first
 * statement of the body ran, `GenericExceptionMapper` rendered 500, and a `requireNotNull` written
 * inside the body would have compiled to nothing. Verified by reverting the fix and re-running:
 * this test reports 500.
 *
 * Only real HTTP can observe it — calling `export(...)` from a unit test supplies the argument JAX-RS
 * does not, so such a test passes against the broken code.
 *
 * The second test is the control: with both dates present the request gets past the guard and fails
 * further downstream, so the first test's redness is attributable to the guard alone.
 */
@QuarkusTest
@TestProfile(StatementMissingParamStatusIT.AuthzOffProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
class StatementMissingParamStatusIT {

    /**
     * `authz.enforce` defaults to true with no OPA sidecar under test, so `@Authorize` endpoints
     * fail CLOSED with 503 before the handler is entered — the request would never reach the
     * parameter binding this test is about. Literal values only: a profile loads in a different
     * classloader from the test class, so anything computed here is computed twice.
     */
    class AuthzOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @TestSecurity(user = "tester", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    @Test
    fun `an ad-hoc export with no from or to answers 400`() {
        Given { this } When {
            get("/api/v1/statements/$ACCOUNT_ID/CZK/export")
        } Then {
            statusCode(400)
        }
    }

    @TestSecurity(user = "tester", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    @Test
    fun `an ad-hoc export with both dates present gets past the guard`() {
        val status = io.restassured.RestAssured
            .given()
            .get("/api/v1/statements/$ACCOUNT_ID/CZK/export?from=2026-01-01&to=2026-01-31")
            .statusCode
        assertThat(status)
            .describedAs("with both dates supplied the request must get past the #3624 guard")
            .isNotEqualTo(400)
    }

    private companion object {
        const val ACCOUNT_ID = "11111111-2222-3333-4444-555555555555"
    }
}
