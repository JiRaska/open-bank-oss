// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.ratelimit

import com.openbank.vop.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Drives a REAL HTTP request through the rate-limit filter against a real Valkey (#13049).
 *
 * The unit test mocks the limiter, so it cannot see which thread the filter runs on. On sandbox the
 * filter ran on the Vert.x event loop, the blocking Redis client threw `The current thread cannot be
 * blocked`, the fail-closed catch turned that into STORE_UNAVAILABLE, and every verify answered 429
 * while Valkey was healthy. Only a request through the running HTTP stack reproduces that: against
 * the blocking limiter the first assertion below fails with a 429.
 *
 * A non-domestic IBAN keeps the request inside vop-service (NO_DATA / NO_SCHEME_CONNECTIVITY, no
 * outbound call), so a 200 here is the filter letting the request through, nothing else.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(VopRateLimitHttpIT.SmallLimit::class)
class VopRateLimitHttpIT {

    class SmallLimit : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.vop.rate-limit.enabled" to "true",
            "openbank.vop.rate-limit.requests-per-minute" to LIMIT.toString(),
            // No OPA sidecar in the test: advisory mode, so authorization is not what answers.
            "authz.enforce" to "false",
        )
    }

    private fun verify(): Int = given()
        .contentType(ContentType.JSON)
        .body("""{"creditorIban":"DE89370400440532013000","creditorName":"Max Mustermann"}""")
        .`when`().post("/api/v1/vop/verify")
        .then().extract().statusCode()

    @Test
    @TestSecurity(user = "vop-it-under-limit", roles = ["ROLE_OPERATOR"])
    fun `a request under the limit is answered 200, not 429`() {
        assertThat(verify()).isEqualTo(200)
    }

    @Test
    @TestSecurity(user = "vop-it-over-limit", roles = ["ROLE_OPERATOR"])
    fun `the request past the limit is answered 429`() {
        assertThat(verify()).isEqualTo(200)
        // A minute-window boundary inside the loop resets the counter once, so allow two windows'
        // worth before declaring that the limit never bit.
        val statuses = (1..2 * LIMIT + 1).map { verify() }
        assertThat(statuses).contains(429)
        assertThat(statuses.takeWhile { it != 429 }).allMatch { it == 200 }
    }

    private companion object {
        const val LIMIT = 3
    }
}
