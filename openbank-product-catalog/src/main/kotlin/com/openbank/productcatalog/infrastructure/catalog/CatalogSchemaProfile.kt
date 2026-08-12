// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.JsonNode
import jakarta.enterprise.context.ApplicationScoped

/** ADR-0258's deliberately small, deterministic JSON Schema profile. */
@ApplicationScoped
class CatalogSchemaProfile {
    fun requireValid(document: JsonNode, expectedId: String? = null) {
        require(document.isObject) { "catalog schema must be a JSON object" }
        require(document.toString().toByteArray().size <= MAX_SCHEMA_BYTES) { "catalog schema is too large" }
        require(document.get("\$schema")?.asText() == DIALECT) { "catalog schema must use JSON Schema 2020-12" }
        expectedId?.let {
            require(document.get("\$id")?.asText() == it) { "catalog schema declares a mismatched schema id" }
        }
        visit(document, depth = 0)
    }

    fun requireValidInstance(document: JsonNode) {
        require(document.toString().toByteArray().size <= MAX_INSTANCE_BYTES) { "catalog instance is too large" }
        visitInstance(document, depth = 0)
    }

    private fun visit(node: JsonNode, depth: Int) {
        require(depth <= MAX_NESTING_DEPTH) { "catalog schema nesting exceeds $MAX_NESTING_DEPTH" }
        when {
            node.isObject -> {
                val ref = node.get("\$ref")?.asText()
                require(ref == null || ref.startsWith("#/\$defs/")) {
                    "catalog schema references must target local \$defs"
                }
                require(!node.has("\$dynamicRef")) { "catalog schema must not use dynamic references" }
                require(depth == 0 || !node.has("\$id")) { "nested schema ids are forbidden" }
                if (node.get("type")?.asText() == "object") {
                    require(
                        node.get("additionalProperties")?.isBoolean == true &&
                            !node.get("additionalProperties").asBoolean(),
                    ) {
                        "every object schema must set additionalProperties to false"
                    }
                }
                node.elements().forEachRemaining { visit(it, depth + 1) }
            }
            node.isArray -> node.elements().forEachRemaining { visit(it, depth + 1) }
        }
    }

    private fun visitInstance(node: JsonNode, depth: Int) {
        require(depth <= MAX_NESTING_DEPTH) { "catalog instance nesting exceeds $MAX_NESTING_DEPTH" }
        when {
            node.isObject || node.isArray -> node.elements().forEachRemaining { visitInstance(it, depth + 1) }
        }
    }

    companion object {
        const val DIALECT = "https://json-schema.org/draft/2020-12/schema"
        const val MAX_SCHEMA_BYTES = 256 * 1024
        const val MAX_INSTANCE_BYTES = 1024 * 1024
        const val MAX_NESTING_DEPTH = 64
    }
}
