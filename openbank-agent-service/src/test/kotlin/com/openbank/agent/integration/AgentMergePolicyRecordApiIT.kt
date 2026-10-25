// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.domain.mergerecord.MergeAuthorization
import com.openbank.agent.domain.mergerecord.MergeAuthorizationKind
import com.openbank.agent.domain.mergerecord.MergeAuthorizationScope
import com.openbank.agent.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
class AgentMergePolicyRecordApiIT {
    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    private fun appAuthorization() = MergeAuthorization(
        approvedBy = "fixture-owner",
        approvedById = 1234,
        approvedAt = Instant.parse("2026-10-01T11:59:00Z"),
        evidenceUrl = "https://github.com/JiRaska/open-bank-oss/actions/runs/4321",
        statusId = 5678,
        workflowRunId = 4321,
    )

    private fun appBody(prNumber: Long): Map<String, Any> = mapOf(
        "agentId" to "fixture-agent", "prNumber" to prNumber,
        "prUrl" to "https://github.com/JiRaska/open-bank-oss/pull/$prNumber",
        "headBranch" to "agent/fixture-agent", "mergedBy" to "openbank-gitops-bot[bot]",
        "mergedAt" to "2026-10-24T12:00:00Z", "title" to "fixture merge",
        "mergedByType" to "GITHUB_APP", "mergedById" to 9876,
        "headSha" to "a".repeat(40), "mergeCommitSha" to "b".repeat(40),
    )

    private fun legacyAuthorization(prNumber: Long) = MergeAuthorization(
        approvedBy = "fixture-owner",
        approvedById = 1234,
        approvedAt = Instant.parse("2026-10-01T11:59:00Z"),
        evidenceUrl = "https://github.com/JiRaska/open-bank-oss/pull/$prNumber",
        kind = MergeAuthorizationKind.LEGACY_PR_AUTO_MERGE,
        scope = MergeAuthorizationScope.PR,
        pullRequestNodeId = "PR_fixture",
        mergeEventNodeId = "ME_fixture",
        mergeEventAt = Instant.parse("2026-10-01T12:00:01Z"),
        observedAt = Instant.parse("2026-10-24T12:00:00Z"),
    )

    private fun policyBody(prNumber: Long): Map<String, Any> = (appBody(prNumber) - "authorization") + mapOf(
        "prUrl" to "https://github.com/JiRaska/open-bank-oss/pull/$prNumber",
        "mergedAt" to "2026-10-24T12:00:00Z",
        "policyAuthorization" to legacyAuthorization(prNumber).copy(
            kind = MergeAuthorizationKind.POLICY_PR_AUTO_MERGE,
            mergeEventAt = Instant.parse("2026-10-24T12:00:00Z"),
            observedAt = Instant.parse("2026-10-24T12:00:01Z"),
            policyCommitSha = "c".repeat(40),
            policyBlobSha = "d".repeat(40),
        ),
    )

    @Test
    @TestSecurity(user = "recorder", roles = ["ROLE_MERGE_RECORDER"])
    fun `modern policy PR proof survives storage and retry without becoming head or legacy proof`() {
        val body = policyBody(97001)
        given().contentType("application/json").body(body).post("/api/v1/agent-merge-records/app-policy")
            .then().statusCode(200).body("inserted", equalTo(true))
            .body("authorization", equalTo(null))
            .body("policyAuthorization.kind", equalTo("POLICY_PR_AUTO_MERGE"))
            .body("policyAuthorization.scope", equalTo("PR"))
            .body("mergedBy", equalTo("openbank-gitops-bot[bot]"))
        val proof = body["policyAuthorization"] as MergeAuthorization
        given().contentType("application/json").body(
            body + (
                "policyAuthorization" to proof.copy(
                    observedAt = proof.observedAt?.plusSeconds(1),
                )
                ),
        ).post("/api/v1/agent-merge-records/app-policy")
            .then().statusCode(200).body("inserted", equalTo(false))
            .body("policyAuthorization.observedAt", equalTo(proof.observedAt.toString()))
        given().get("/api/v1/agent-merge-records/97001").then().statusCode(200)
            .body("authorization", equalTo(null))
            .body("policyAuthorization.policyCommitSha", equalTo("c".repeat(40)))
            .body("policyAuthorization.policyBlobSha", equalTo("d".repeat(40)))
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT policy_commit_sha, policy_blob_sha FROM agent_merge_record WHERE pr_number = 97001",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("c".repeat(40))
                    assertThat(rows.getString(2)).isEqualTo("d".repeat(40))
                }
            }
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT payload FROM agent_audit_outbox WHERE payload::jsonb ->> 'pr_number' = '97001'",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    val payload = objectMapper.readTree(rows.getString(1))
                    assertThat(payload["authorization"]["kind"].asText()).isEqualTo("POLICY_PR_AUTO_MERGE")
                    assertThat(payload["authorization"]["policyBlobSha"].asText()).isEqualTo("d".repeat(40))
                    assertThat(rows.next()).isFalse()
                }
            }
        }
        for (changed in listOf(
            proof.copy(policyCommitSha = "e".repeat(40)),
            proof.copy(policyBlobSha = "f".repeat(40)),
        )) {
            given().contentType("application/json").body(body + ("policyAuthorization" to changed))
                .post("/api/v1/agent-merge-records/app-policy").then().statusCode(409)
        }
    }

    @Test
    @TestSecurity(user = "recorder", roles = ["ROLE_MERGE_RECORDER"])
    fun `old route stays closed and policy route rejects mixed proof and codex downgrade`() {
        val body = policyBody(97002)
        given().contentType("application/json").body(body + ("authorization" to body.getValue("policyAuthorization")))
            .post("/api/v1/agent-merge-records/app").then().statusCode(400)
        given().contentType("application/json").body(body + ("authorization" to appAuthorization()))
            .post("/api/v1/agent-merge-records/app-policy").then().statusCode(400)
        given().contentType("application/json").body(body + ("authorization" to appAuthorization()))
            .post("/api/v1/agent-merge-records/app").then().statusCode(400)
        given().contentType("application/json").body(body + ("headBranch" to "codex/fixture"))
            .post("/api/v1/agent-merge-records/app-policy").then().statusCode(400)
        given().get("/api/v1/agent-merge-records/capabilities").then().statusCode(200)
            .body("evidenceSchemaVersion", equalTo(2)).body("policyPrAuthorization", equalTo(true))
        given().get("/api/v1/agent-merge-records/97002").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `policy route requires recorder role`() {
        given().contentType("application/json").body(policyBody(97003))
            .post("/api/v1/agent-merge-records/app-policy").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "recorder", roles = ["ROLE_MERGE_RECORDER"])
    fun `policy timestamp tolerance is symmetric and rejects fractional overflow before storage`() {
        val offsets = listOf(-60000001L to 400, -60000000L to 200, 60000000L to 200, 60000001L to 400)
        for ((index, pair) in offsets.withIndex()) {
            val (offset, expected) = pair
            val body = policyBody(97200L + index)
            val proof = body["policyAuthorization"] as MergeAuthorization
            val event = Instant.parse("2026-10-24T12:00:00Z").plusNanos(offset * 1000)
            val changed = proof.copy(mergeEventAt = event, observedAt = event.plusSeconds(120))
            given().contentType("application/json").body(body + ("policyAuthorization" to changed))
                .post("/api/v1/agent-merge-records/app-policy").then().statusCode(expected)
        }
    }
}
