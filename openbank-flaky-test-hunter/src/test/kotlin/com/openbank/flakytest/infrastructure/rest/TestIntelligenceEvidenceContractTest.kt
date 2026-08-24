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

        assertThat(post["operationId"]).isEqualTo("analyzeTestIntelligenceEvidence")
        assertThat(post.toString()).contains("TestIntelligenceAnalysisRequest", "ROLE_ADMIN")
        assertThat(paths.keys).noneMatch { it.contains("apply") || it.contains("remediation") }
    }
}
