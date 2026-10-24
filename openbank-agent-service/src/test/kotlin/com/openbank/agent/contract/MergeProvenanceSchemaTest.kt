// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** Validate native OpenAPI 3.1 null unions; 3.0's nullable flag is ignored by JSON Schema. */
class MergeProvenanceSchemaTest {
    private val mapper = ObjectMapper()
    private val document = Yaml().load<Map<String, Any>>(File("src/main/resources/openapi.yaml").readText())
    private val root = mapper.valueToTree<ObjectNode>(document)
        .put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        .put("\$ref", "#/components/schemas/MergeRecordIngestRequest")
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root)
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

    private fun validate(value: Any) = schema.validate(mapper.valueToTree(value))

    @Test
    fun `legacy human requests accept omitted and explicitly null new provenance fields`() {
        assertThat(validate(human)).isEmpty()
        assertThat(
            validate(
                human + mapOf(
                    "authorization" to null,
                    "mergedById" to null,
                    "headSha" to null,
                    "mergeCommitSha" to null,
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
    fun `App actors require complete non-null provenance and human actors prohibit authorization`() {
        val bareApp = human + mapOf("mergedBy" to "openbank-gitops-bot[bot]", "mergedByType" to "GITHUB_APP")
        val invalid = listOf(
            bareApp, app - "authorization", app - "headSha", app - "mergeCommitSha", app - "mergedById",
            app + ("authorization" to null), app + ("headSha" to null),
            app + ("mergeCommitSha" to null), app + ("mergedById" to null),
            human + ("authorization" to exactProof),
            human + mapOf("mergedByType" to "HUMAN", "authorization" to exactProof),
        )
        invalid.forEach { assertThat(validate(it)).isNotEmpty() }
    }
}
