// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
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
import io.restassured.response.Response
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The whole agent-maker CLEAR through real HTTP (#16455 review): the agent's CLEAR is paused with
 * a pending approval, a DIFFERENT human approves it, and the agent's retry carrying the
 * `X-Approval-Id` header executes and records the human as reviewer.
 *
 * Step 4 is the regression this exists for. While the resource took `X-Approval-Id` as a method
 * parameter, `@Authorize` fingerprinted it with the business arguments: the first call bound
 * `approvalId = null`, the retry `approvalId = "<id>"`, the approval never matched, and every
 * retry minted a fresh PENDING approval — 202 forever, the agent path dead. A unit test that calls
 * the resource class cannot see that, because the fingerprint is taken by the CDI interceptor.
 *
 * The PDP stub flags `four_eyes_required` for `sanctions.clear` ONLY; flagging the decide action
 * too would pause the approval of the approval and make the negative steps pass vacuously (see
 * MergeSweepApprovalBindingIT in transaction-service for the measured failure).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(AgentClearFourEyesFlowIT.FourEyesProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentClearFourEyesFlowIT {

    // No @Priority: that would enable the stub for EVERY @QuarkusTest in the module (it leaked
    // into AgentReviewGuardHttpTest as a 202). The profile alone selects it.
    @Alternative
    @ApplicationScoped
    class ClearIsFourEyesPdp : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision = AuthzDecision(
            allow = true,
            attributes = mapOf("four_eyes_required" to (query.action == "sanctions.clear" && gateClear)),
        )
    }

    class FourEyesProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("authz.enforce" to "true", "authz.four-eyes.enforce" to "true")

        override fun getEnabledAlternatives() = setOf<Class<*>>(ClearIsFourEyesPdp::class.java)
    }

    private fun clear(approvalId: String?): Response = given()
        .contentType(ContentType.JSON)
        .apply { if (approvalId != null) header("X-Approval-Id", approvalId) }
        .body("""{"checkId":"$checkId","note":"Name-only fuzzy match; DOB differs.","newStatus":"CLEAR"}""")
        .post("/api/v3/sanctions/review")

    @Test
    @Order(0)
    @TestSecurity(user = CHECKER, roles = ["ROLE_OPERATOR"])
    fun `a check exists to review`() {
        checkId = given()
            .contentType(ContentType.JSON)
            .body("""{"entityType":"INDIVIDUAL","name":"Agent Clear Probe","idempotencyKey":"${UUID.randomUUID()}"}""")
            .post("/api/v3/sanctions/screen")
            .then().statusCode(201)
            .extract().path("id")
    }

    @Test
    @Order(1)
    @TestSecurity(user = AGENT, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent-clear-four-eyes-flow"),
            Claim(key = "azp", value = "openbank-agent"),
            Claim(key = "preferred_username", value = AGENT),
        ],
    )
    fun `the agent CLEAR is paused with a pending approval`() {
        val paused = clear(null)
        assertThat(paused.statusCode).isEqualTo(PENDING_APPROVAL)
        approvalId = paused.jsonPath().getString("approvalId")
        assertThat(approvalId).isNotBlank()
    }

    @Test
    @Order(2)
    @TestSecurity(user = AGENT, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent-clear-four-eyes-flow"),
            Claim(key = "azp", value = "openbank-agent"),
            Claim(key = "preferred_username", value = AGENT),
        ],
    )
    fun `the agent retry before any decision does not execute`() {
        assertThat(clear(approvalId).statusCode).isEqualTo(PENDING_APPROVAL)
    }

    @Test
    @Order(3)
    @TestSecurity(user = CHECKER, roles = ["ROLE_OPERATOR"])
    fun `a different human approves it`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"approve": true}""")
            .patch("/api/v1/sanctions/approvals/$approvalId")
            .then().statusCode(200)
    }

    @Test
    @Order(4)
    @TestSecurity(user = AGENT, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent-clear-four-eyes-flow"),
            Claim(key = "azp", value = "openbank-agent"),
            Claim(key = "preferred_username", value = AGENT),
        ],
    )
    fun `the agent retry with the approval executes and records the human checker`() {
        val done = clear(approvalId)
        assertThat(done.statusCode).describedAs(done.body.asString()).isEqualTo(200)
        assertThat(done.jsonPath().getString("status")).isEqualTo("CLEAR")
        assertThat(done.jsonPath().getString("reviewedBy")).isEqualTo(CHECKER)
    }

    @Test
    @Order(5)
    @TestSecurity(user = AGENT, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent-clear-four-eyes-flow"),
            Claim(key = "azp", value = "openbank-agent"),
            Claim(key = "preferred_username", value = AGENT),
        ],
    )
    fun `the consumed approval cannot be replayed`() {
        assertThat(clear(approvalId).statusCode).isEqualTo(PENDING_APPROVAL)
    }

    /**
     * The binding FourEyesConsumption exists for: the policy stops gating sanctions.clear (a bundle
     * regression), so four-eyes does not run and consumes nothing in this request. The agent then
     * replays the id of the approval step 4 consumed — EXECUTED, right check, right maker, a real
     * human checker. Only "consumed by THIS request" refuses it.
     */
    @Test
    @Order(6)
    @TestSecurity(user = AGENT, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent-clear-four-eyes-flow"),
            Claim(key = "azp", value = "openbank-agent"),
            Claim(key = "preferred_username", value = AGENT),
        ],
    )
    fun `a consumed approval cannot be replayed even when the policy stops gating the CLEAR`() {
        gateClear = false
        try {
            assertThat(clear(approvalId).statusCode).isEqualTo(403)
        } finally {
            gateClear = true
        }
    }

    companion object {
        const val AGENT = "service-account-openbank-agent"
        const val CHECKER = "officer-1"
        const val PENDING_APPROVAL = 202

        // Literals only would be safer across the profile classloader (CLAUDE.md), but these are
        // written and read by the test class alone, never by the profile.
        lateinit var checkId: String
        lateinit var approvalId: String

        @Volatile
        var gateClear = true
    }
}
