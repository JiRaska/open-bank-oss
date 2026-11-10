// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.openbank.devops.domain.model.CiGateProfileEvidence
import com.openbank.devops.domain.model.DeliveryEvidenceState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class CiGateProfileContractTest {
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @Test
    fun `unavailable evidence round trips without inventing zero cost or provenance`() {
        val json = """
            {"repository":"example/project","observedAt":"2026-11-08T07:00:00Z",
             "state":"UNAVAILABLE","reason":"BUDGET_OR_BACKOFF","provenance":null,"gates":[]}
        """.trimIndent()
        val evidence = mapper.readValue(json, CiGateProfileEvidence::class.java)
        val output = mapper.readTree(mapper.writeValueAsBytes(evidence))
        assertThat(evidence.state).isEqualTo(DeliveryEvidenceState.UNAVAILABLE)
        assertThat(output["provenance"].isNull).isTrue()
        assertThat(output["gates"].isArray).isTrue()
        assertThat(output["gates"].size()).isZero()
        assertThat(output.has("estimatedSaving")).isFalse()
    }

    @Test
    fun `trigger contract exposes optional typed profile without changing its route`() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/openapi.yaml"))
        val document = stream.use { Yaml().load<Map<String, Any>>(it) }
        val tree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(document)
        assertThat(tree.at("/info/version").asText()).isEqualTo("1.3.0")
        val report = tree.at("/components/schemas/DevOpsRunReport/properties/ciGateProfile")
        assertThat(report.at("/\$ref").asText())
            .isEqualTo("#/components/schemas/CiGateProfileEvidence")
        val evidence = tree.at("/components/schemas/CiGateProfileEvidence")
        assertThat(evidence["required"].map { it.asText() })
            .contains("repository", "observedAt", "state", "gates")
        assertThat(evidence.at("/properties/state/enum").map { it.asText() })
            .containsExactly("COMPLETE", "PARTIAL", "UNAVAILABLE")
        assertThat(tree.at("/paths/~1api~1v1~1devops~1analysis~1trigger/post/responses/200").isMissingNode)
            .isFalse()
    }

    @Test
    fun `published response schema accepts absent and null profiles but rejects false scalar evidence`() {
        val document = checkNotNull(javaClass.getResourceAsStream("/openapi.yaml")).use {
            Yaml().load<Map<String, Any>>(it)
        }
        val spec = mapper.valueToTree<JsonNode>(document)
        val response = spec.at(
            "/paths/~1api~1v1~1devops~1analysis~1trigger/post/responses/200/content/application~1json/schema",
        )
        val root = mapper.createObjectNode()
        root.set<JsonNode>("components", nullableTypes(spec["components"]))
        root.set<JsonNode>("allOf", mapper.createArrayNode().add(response))
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(mapper.writeValueAsString(root), InputFormat.JSON)
        for (body in listOf(
            "{}",
            """{"ciGateProfile":null}""",
            """{"ciGateProfile":{"repository":"example/project","observedAt":"2026-11-08T07:00:00Z",
                "state":"UNAVAILABLE","reason":"API_BUDGET","provenance":null,"gates":[]}}""",
        )) {
            assertThat(schema.validate(body, InputFormat.JSON)).describedAs(body).isEmpty()
        }
        assertThat(schema.validate("""{"ciGateProfile":false}""", InputFormat.JSON)).isNotEmpty()
    }

    // OpenAPI 3.0 nullable changes only an explicitly declared type in that same schema.
    private fun nullableTypes(node: JsonNode): JsonNode {
        when (node) {
            is ObjectNode -> {
                node.fieldNames().asSequence().toList().forEach { key ->
                    node.set<JsonNode>(key, nullableTypes(node[key]))
                }
                if (node.path("nullable").asBoolean() && node.path("type").isTextual) {
                    node.set<JsonNode>("type", mapper.createArrayNode().add(node["type"].asText()).add("null"))
                }
            }
            is ArrayNode -> for (i in 0 until node.size()) node.set(i, nullableTypes(node[i]))
        }
        return node
    }
}
