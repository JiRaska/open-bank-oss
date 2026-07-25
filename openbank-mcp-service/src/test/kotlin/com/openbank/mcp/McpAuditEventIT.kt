// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditResult
import io.quarkus.test.junit.QuarkusTest
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
 */
@QuarkusTest
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
    fun `an allowed tool call emits an AI-attributed audit event`() {
        toolsCall(TestPolicyDecisionPoint.ALLOWED_TOOL)

        val event = singleEvent()
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.actorId).isEqualTo("agent:mcp-anonymous")
        assertThat(event.operation).isEqualTo("mcp.tool.call")
        assertThat(event.resourceType).isEqualTo("mcp.tool")
        assertThat(event.resourceId).isEqualTo(TestPolicyDecisionPoint.ALLOWED_TOOL)
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(event.payload)
            .containsEntry("policy_decision", "ALLOW")
            .containsEntry("charter", "mcp-anonymous")
            .containsEntry("capability", "query.account.readonly")
    }

    @Test
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
    fun `the audit event records argument key names but never their values`() {
        toolsCall(TestPolicyDecisionPoint.DENIED_TOOL, """{"accountId":"CZ6508000000192000145399"}""")

        val event = singleEvent()
        assertThat(event.payload["argument_keys"]).isEqualTo(listOf("accountId"))
        assertThat(event.payload.values.map { it.toString() })
            .noneMatch { it.contains("CZ6508000000192000145399") }
    }

    @Test
    fun `tools list is not audited - no customer data is touched`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .post("/mcp")
            .then()
            .statusCode(200)

        assertThat(recorder.events).isEmpty()
    }
}
