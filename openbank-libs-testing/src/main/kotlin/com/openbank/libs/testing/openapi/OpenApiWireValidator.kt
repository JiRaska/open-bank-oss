// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.testing.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Validates a REAL response body against the 200 schema an `openapi.yaml` publishes for an
 * operation (#13210). `oasdiff` only compares a spec with its previous version, so a response
 * schema that never matched the implementation stays wrong forever; a service test that drives its
 * real endpoint and feeds the body here closes that gap.
 *
 * The validator and YAML parser are `implementation` dependencies of this module on purpose: they
 * are not part of its public API, so no consumer compiles against them.
 */
class OpenApiWireValidator private constructor(specYaml: String) {
    private val mapper = ObjectMapper()
    private val spec: JsonNode = mapper.valueToTree<JsonNode>(
        Load(LoadSettings.builder().build()).loadFromString(specYaml),
    ).let { parsed ->
        // Only OpenAPI 3.0 defines nullable. A 3.1 document is already JSON Schema:
        // translating its annotations would validate a different contract than it publishes.
        if (parsed.path("openapi").asText().startsWith("3.0.")) nullableToType(parsed) else parsed
    }

    /** Loads `openapi.yaml` from the test classpath (a service's main resources). */
    constructor() : this(
        specYaml =
        checkNotNull(OpenApiWireValidator::class.java.classLoader.getResourceAsStream("openapi.yaml")) {
            "openapi.yaml not on the test classpath"
        }.bufferedReader().use { it.readText() },
    )

    /** Violations of [body] against the 200 JSON response schema of `GET` [specPath]; empty = conforms. */
    fun violations(body: String, specPath: String): List<String> {
        val method = "get"
        val status = "200"
        val schema = spec.path("paths").path(specPath).path(method).path("responses").path(status)
            .path("content").path("application/json").path("schema")
        require(!schema.isMissingNode) { "no $status JSON schema for ${method.uppercase()} $specPath" }
        // Root: components (so '#/components/schemas/X' resolves) + the operation's schema.
        val root = mapper.createObjectNode()
        root.set<JsonNode>("components", spec.path("components"))
        root.set<JsonNode>("allOf", mapper.createArrayNode().add(schema))
        val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
        val compiled = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(mapper.writeValueAsString(root), InputFormat.JSON, config)
        return compiled.validate(body, InputFormat.JSON).map { it.message }
    }

    /** OpenAPI 3.0 `nullable: true` expressed as a JSON Schema null type. */
    private fun nullableToType(node: JsonNode): JsonNode {
        when (node) {
            is ObjectNode -> {
                node.fieldNames().asSequence().toList().forEach { f ->
                    node.set<JsonNode>(f, nullableToType(node.get(f)))
                }
                if (node.path("nullable").asBoolean(false) && node.get("type")?.isTextual == true) {
                    val types: ArrayNode = mapper.createArrayNode().add(node.get("type").asText()).add("null")
                    node.set<JsonNode>("type", types)
                }
            }
            is ArrayNode -> for (i in 0 until node.size()) node.set(i, nullableToType(node.get(i)))
            else -> Unit
        }
        return node
    }
}
