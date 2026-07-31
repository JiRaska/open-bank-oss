// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditResult
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Drives `tools/call` over the REAL HTTP stack and the REAL CDI graph and asserts the ADR-0031 D5
 * audit event actually leaves the endpoint — for an ALLOWED call and for a DENIED one.
 *
 * The plain-unit tests in `McpEndpointTest` build the endpoint themselves, so they can only prove
 * that the code path calls the auditor it was handed. What they cannot prove — and what this test
 * exists for — is that the container resolves `McpCallAuditor` and its `AuditEventPublisher` at
 * all, which is the way an audit trail actually goes missing in production: silently, with every
 * unit test green.
 *
 * ADR-0195 step 4: the phase-1 placeholder identity is gone, so a real `tools/call` needs a
 * validated agent token. `@TestSecurity` + `@OidcSecurity` simulate one (`sub` + `consent_id`)
 * without a real IdP round-trip — the same mechanism the fleet already uses for OIDC resource-server
 * tests (see `openbank-agent-service`).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class McpAuditEventIT {

    @Inject
    lateinit var recorder: RecordingAuditEventPublisher

    @BeforeEach
    fun reset() = recorder.clear()

    private fun toolsCall(tool: String, arguments: String = "{}") {
        given()
            .contentType(ContentType.JSON)
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""")
            .post("/mcp")
            .then()
            .statusCode(200)
    }

    private fun singleEvent(): AuditEvent {
        assertThat(recorder.events).hasSize(1)
        return recorder.events.single()
    }

    @Test
    @TestSecurity(user = "agent:test-agent")
    @OidcSecurity(
        claims = [Claim(key = "sub", value = "agent:test-agent"), Claim(key = "consent_id", value = TEST_CONSENT_ID)],
    )
    fun `an allowed tool call emits an AI-attributed audit event`() {
        toolsCall(TestPolicyDecisionPoint.ALLOWED_TOOL)

        val event = singleEvent()
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.actorId).isEqualTo("agent:test-agent")
        assertThat(event.operation).isEqualTo("mcp.tool.call")
        assertThat(event.resourceType).isEqualTo("mcp.tool")
        assertThat(event.resourceId).isEqualTo(TestPolicyDecisionPoint.ALLOWED_TOOL)
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(event.payload)
            .containsEntry("policy_decision", "ALLOW")
            .containsEntry("charter", "test-agent")
            .containsEntry("capability", "query.account.readonly")
    }

    @Test
    @TestSecurity(user = "agent:test-agent")
    @OidcSecurity(
        claims = [Claim(key = "sub", value = "agent:test-agent"), Claim(key = "consent_id", value = TEST_CONSENT_ID)],
    )
    fun `a denied tool call emits a DENIED audit event carrying the policy reason`() {
        toolsCall(TestPolicyDecisionPoint.DENIED_TOOL)

        val event = singleEvent()
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.resourceId).isEqualTo(TestPolicyDecisionPoint.DENIED_TOOL)
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.payload)
            .containsEntry("policy_decision", "DENY")
            .containsEntry("reason", "no matching allow rule")
    }

    @Test
    @TestSecurity(user = "agent:test-agent")
    @OidcSecurity(
        claims = [Claim(key = "sub", value = "agent:test-agent"), Claim(key = "consent_id", value = TEST_CONSENT_ID)],
    )
    fun `the audit event records argument key names but never their values`() {
        toolsCall(TestPolicyDecisionPoint.DENIED_TOOL, """{"accountId":"CZ6508000000192000145399"}""")

        val event = singleEvent()
        assertThat(event.payload["argument_keys"]).isEqualTo(listOf("accountId"))
        assertThat(event.payload.values.map { it.toString() })
            .noneMatch { it.contains("CZ6508000000192000145399") }
    }

    @Test
    fun `tools list is audited - discovery reconnaissance leaves a trace (ADR-0225 D4)`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .post("/mcp")
            .then()
            .statusCode(200)

        // Anonymous discovery is fail-closed (empty list) AND on the record: before ADR-0225 a
        // caller could enumerate the whole operations vocabulary without a single audit event.
        val event = singleEvent()
        assertThat(event.operation).isEqualTo("mcp.tools.list")
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.payload["reason"]).isEqualTo("caller authentication failed")
        assertThat(event.payload["tools_total"]).isEqualTo(6)
    }

    // ADR-0195 step 4 (BLOCKER #2206): a tools/call with NO agent token must be denied, never
    // silently allowed via a placeholder identity — the exact vulnerability this cutover closes.
    @Test
    fun `a tool call with no agent token is denied and audited as unavailable`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                    """"params":{"name":"${TestPolicyDecisionPoint.ALLOWED_TOOL}","arguments":{}}}""",
            )
            .post("/mcp")
            .then()
            .statusCode(200)
            .body("result.isError", org.hamcrest.Matchers.equalTo(true))
            .body("result.content[0].text", org.hamcrest.Matchers.equalTo("Authorization unavailable"))

        val event = singleEvent()
        assertThat(event.actorId).isEqualTo("unknown")
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.payload).containsEntry("reason", "caller authentication failed")
    }

    private companion object {
        const val TEST_CONSENT_ID = "11111111-1111-1111-1111-111111111111"
    }
}
