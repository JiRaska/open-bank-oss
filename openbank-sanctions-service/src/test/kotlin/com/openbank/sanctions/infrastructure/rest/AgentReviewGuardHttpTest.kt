// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import java.util.UUID

private const val REVIEW = "/api/v3/sanctions/review"

private fun review(newStatus: String) = given()
    .contentType(ContentType.JSON)
    .body(
        """{"checkId":"${UUID.randomUUID()}","note":"Name-only fuzzy match; DOB differs.","newStatus":"$newStatus"}""",
    )
    .`when`().post(REVIEW)
    .then()

/**
 * Real HTTP through @RolesAllowed, AgentReviewGuard and the @Authorize interceptor (#16455 review).
 *
 * The profile turns authorization ENFORCEMENT on with no OPA sidecar present, so the @Authorize
 * interceptor fails closed with 503 on every call that reaches it. That is the instrument: an agent
 * request answering 403 instead of 503 was refused BEFORE the interceptor — so it could not have
 * created a pending four-eyes approval — while an allowed agent request and a human request reach
 * the interceptor and answer 503.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(AgentReviewGuardHttpTest.EnforcedFourEyes::class)
class AgentReviewGuardHttpTest {

    class EnforcedFourEyes : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("authz.enforce" to "true", "authz.four-eyes.enforce" to "true")
    }

    @Test
    @TestSecurity(user = "service-account-openbank-agent", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent-review-guard-http"),
            Claim(key = "azp", value = "openbank-agent"),
            Claim(key = "preferred_username", value = "service-account-openbank-agent"),
        ],
    )
    fun `an agent non-CLEAR review is refused before the four-eyes gate`() {
        listOf("HIT", "POTENTIAL_HIT", "WHITELISTED", "ESCALATED").forEach { review(it).statusCode(403) }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-agent", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent-review-guard-http"),
            Claim(key = "azp", value = "openbank-agent"),
            Claim(key = "preferred_username", value = "service-account-openbank-agent"),
        ],
    )
    fun `an agent CLEAR passes the guard and reaches the authorization gate`() {
        review("CLEAR").statusCode(503)
    }

    @Test
    @TestSecurity(user = "officer-1", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "officer-1-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "officer-1"),
        ],
    )
    fun `a human non-CLEAR review is not touched by the agent guard`() {
        review("HIT").statusCode(503)
    }
}

/** With four-eyes off, the agent path fails closed even for CLEAR — before anything else runs. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(AgentReviewGuardFourEyesOffHttpTest.FourEyesOff::class)
class AgentReviewGuardFourEyesOffHttpTest {

    class FourEyesOff : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("authz.enforce" to "true", "authz.four-eyes.enforce" to "false")
    }

    @Test
    @TestSecurity(user = "service-account-openbank-agent", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent-review-guard-http"),
            Claim(key = "azp", value = "openbank-agent"),
            Claim(key = "preferred_username", value = "service-account-openbank-agent"),
        ],
    )
    fun `an agent CLEAR is refused while four-eyes is not enforced`() {
        review("CLEAR").statusCode(403)
    }
}
