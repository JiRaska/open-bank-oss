// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.flakytest.infrastructure.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class TestIntelligenceEvidenceContractTest {
    @Test
    fun `OpenAPI publishes bounded evidence analysis and no remediation operation`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/openapi.yaml"))

        @Suppress("UNCHECKED_CAST")
        val document = Yaml().load<Map<String, Any>>(stream)
        val paths = document["paths"] as Map<String, Any>
        val analysis = paths["/api/v1/flaky-test-hunter/evidence/analyze"] as Map<String, Any>
        val post = analysis["post"] as Map<String, Any>
        val components = document["components"] as Map<String, Any>
        val schemas = components["schemas"] as Map<String, Any>
        val componentInput = schemas["TestIntelligenceComponentInput"] as Map<String, Any>
        val properties = componentInput["properties"] as Map<String, Any>

        assertThat(post["operationId"]).isEqualTo("analyzeTestIntelligenceEvidence")
        assertThat(post.toString()).contains("TestIntelligenceAnalysisRequest", "ROLE_ADMIN")
        assertThat(properties.keys).contains("flakyTests", "failingTests", "sameCommitTransitions", "wastedDurationMs")
        assertThat(paths.keys).noneMatch { it.contains("apply") || it.contains("remediation") }
    }

    @Test
    fun `OpenAPI distinguishes Testcontainers starts from stop evidence`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/openapi.yaml"))

        @Suppress("UNCHECKED_CAST")
        val document = Yaml().load<Map<String, Any>>(stream)
        val components = document["components"] as Map<String, Any>
        val schemas = components["schemas"] as Map<String, Any>
        val input = schemas["TestIntelligenceComponentInput"] as Map<String, Any>
        val properties = input["properties"] as Map<String, Any>

        assertThat(properties)
            .containsKey("observedInfrastructureStarts")
            .containsKey("observedInfrastructureStops")
        assertThat(properties["observedInfrastructureStops"].toString()).contains("cannot exceed starts")
    }
}
