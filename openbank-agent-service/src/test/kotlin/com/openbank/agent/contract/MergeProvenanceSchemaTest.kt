// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** Validate native OpenAPI 3.1 null unions; 3.0's nullable flag is ignored by JSON Schema. */
@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    restrictToAnnotatedClass = true,
    initArgs = [ResourceArg(name = "db", value = "openbank_agent_it")],
)
class MergeProvenanceSchemaTest {
    private val mapper = ObjectMapper()
    private val document = Yaml().load<Map<String, Any>>(File("src/main/resources/openapi.yaml").readText())
    private fun schema(name: String) = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
        mapper.valueToTree<ObjectNode>(document)
            .put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            .put("\$ref", "#/components/schemas/$name"),
    )
    private val humanSchema = schema("MergeRecordIngestRequest")
    private val appSchema = schema("AppMergeRecordIngestRequest")
    private val human = mapOf(
        "agentId" to "fixture-agent",
        "prNumber" to 1,
        "prUrl" to "https://github.com/JiRaska/open-bank-oss/pull/1",
        "headBranch" to "codex/fixture",
        "mergedBy" to "fixture-reviewer",
        "mergedAt" to "2026-10-01T12:00:00Z",
        "title" to "fixture merge",
    )
    private val exactProof = mapOf(
        "approvedBy" to "fixture-owner",
        "approvedById" to 1234,
        "approvedAt" to "2026-10-01T11:59:00Z",
        "evidenceUrl" to "https://github.com/JiRaska/open-bank-oss/actions/runs/4321",
        "statusId" to 5678,
        "workflowRunId" to 4321,
    )
    private val app = human + mapOf(
        "mergedBy" to "openbank-gitops-bot[bot]",
        "mergedByType" to "GITHUB_APP",
        "mergedById" to 9876,
        "headSha" to "a".repeat(40),
        "mergeCommitSha" to "b".repeat(40),
        "authorization" to exactProof,
    )

    private fun validate(value: Any) = appSchema.validate(mapper.valueToTree(value))

    @Test
    fun `legacy human requests accept omitted and explicitly null new provenance fields`() {
        assertThat(humanSchema.validate(mapper.valueToTree(human))).isEmpty()
        assertThat(
            humanSchema.validate(
                mapper.valueToTree(
                    human + mapOf(
                        "authorization" to null,
                        "mergedById" to null,
                        "headSha" to null,
                        "mergeCommitSha" to null,
                    ),
                ),
            ),
        ).isEmpty()
        assertThat(
            humanSchema.validate(
                mapper.valueToTree(
                    human + mapOf(
                        "mergedByType" to "GITHUB_APP",
                        "authorization" to "ignored legacy extension",
                    ),
                ),
            ),
        ).isEmpty()
    }

    @Test
    fun `exact-head proof and historical PR proof have truthful nullability`() {
        assertThat(validate(app)).isEmpty()
        val legacy = exactProof + mapOf(
            "kind" to "LEGACY_PR_AUTO_MERGE", "scope" to "PR",
            "statusId" to null, "workflowRunId" to null,
            "pullRequestNodeId" to "PR_fixture", "mergeEventNodeId" to "ME_fixture",
            "mergeEventAt" to "2026-10-01T12:00:01Z", "observedAt" to "2026-10-24T12:00:00Z",
            "evidenceUrl" to "https://github.com/JiRaska/open-bank-oss/pull/1",
        )
        assertThat(validate(app + ("authorization" to legacy))).isEmpty()
        assertThat(validate(app + ("authorization" to (legacy + ("scope" to "HEAD"))))).isNotEmpty()
        assertThat(validate(app + ("authorization" to (exactProof + ("statusId" to null))))).isNotEmpty()
    }

    @Test
    fun `null union does not accept arbitrary proof or identifier types`() {
        listOf(
            app + ("authorization" to "forged"),
            app + ("mergedById" to false),
            app + ("headSha" to 123),
            app + ("mergeCommitSha" to "short"),
            app + ("authorization" to (exactProof + ("approvedById" to 0))),
        ).forEach { assertThat(validate(it)).isNotEmpty() }
    }

    @Test
    fun `App endpoint requires complete non-null provenance and rejects human actor input`() {
        val bareApp = human + mapOf("mergedBy" to "openbank-gitops-bot[bot]", "mergedByType" to "GITHUB_APP")
        val invalid = listOf(
            bareApp, app - "authorization", app - "headSha", app - "mergeCommitSha", app - "mergedById",
            app - "mergedByType", app + ("mergedByType" to null), app + ("mergedByType" to "HUMAN"),
            app + ("authorization" to null), app + ("headSha" to null),
            app + ("mergeCommitSha" to null), app + ("mergedById" to null),
            human + ("authorization" to exactProof),
            human + mapOf("mergedByType" to "HUMAN", "authorization" to exactProof),
        )
        invalid.forEach { assertThat(validate(it)).isNotEmpty() }
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `schema-valid App evidence cannot be ingested without the dedicated recorder role`() {
        assertThat(validate(app)).isEmpty()
        given().contentType("application/json").body(app)
            .`when`().post("/api/v1/agent-merge-records/app")
            .then().statusCode(403)
    }

    @Test
    fun `policy proof has its own additive schema without widening the old closed enum`() {
        val proof = mapOf(
            "kind" to "POLICY_PR_AUTO_MERGE", "scope" to "PR", "approvedBy" to "fixture-owner",
            "approvedById" to 1234, "approvedAt" to "2026-10-24T11:59:00Z",
            "evidenceUrl" to "https://github.com/JiRaska/open-bank-oss/pull/1",
            "pullRequestNodeId" to "PR_fixture", "mergeEventNodeId" to "ME_fixture",
            "mergeEventAt" to "2026-10-24T12:00:00Z", "observedAt" to "2026-10-24T12:00:01Z",
            "policyCommitSha" to "c".repeat(40), "policyBlobSha" to "d".repeat(40),
        )
        val policyApp = (app - "authorization") + ("policyAuthorization" to proof)
        val policySchema = schema("PolicyAppMergeRecordIngestRequest")
        assertThat(policySchema.validate(mapper.valueToTree(policyApp))).isEmpty()
        assertThat(validate(app + ("authorization" to proof))).isNotEmpty()
        assertThat(validate(app + ("policyAuthorization" to proof))).isNotEmpty()
        assertThat(validate(app + ("policyAuthorization" to null))).isEmpty()
        assertThat(policySchema.validate(mapper.valueToTree(policyApp + ("authorization" to exactProof)))).isNotEmpty()
        assertThat(policySchema.validate(mapper.valueToTree(policyApp - "policyAuthorization"))).isNotEmpty()
        assertThat(
            schema("PolicyMergeAuthorization").validate(mapper.valueToTree(proof - "policyBlobSha")),
        ).isNotEmpty()
    }
}
