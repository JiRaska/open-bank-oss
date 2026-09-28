// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * The ONE canonical form request bodies are fingerprinted in, so every service hashes the same
 * request to the same [RequestFingerprint] regardless of how the client serialised it.
 *
 * Canonical form:
 *  - object properties sorted by name, recursively (map keys and DTO properties alike);
 *  - no insignificant whitespace;
 *  - every non-integral number is a `BigDecimal` with trailing zeros stripped and written plain,
 *    so **`1.0`, `1.00` and `1` are the same amount** (`10.50` == `10.5`). Integral JSON numbers
 *    are left as integers, so `1` and `1.0` also collide — deliberate: for money a scale
 *    difference is not a different request;
 *  - **a property whose value is `null` is dropped, so `{"a":null}` equals `{}`** (null and
 *    absent mean the same thing to every DTO here). Array elements keep their order and nulls.
 *
 * The DTO is first converted with the caller's own mapper, so its naming strategy, modules and
 * `@JsonProperty`/`@JsonIgnore` annotations still apply. The derived mapper copy is cached per
 * source mapper.
 */
object RequestFingerprints {
    private val canonicalMappers = ConcurrentHashMap<ObjectMapper, ObjectMapper>()

    /** Canonical JSON of [dto], or of the raw JSON text when [dto] is a [String]. `null` → `""`. */
    fun canonical(objectMapper: ObjectMapper, dto: Any?): String {
        if (dto == null) return ""
        val mapper = canonicalMappers.computeIfAbsent(objectMapper, ::canonicalCopy)
        val tree: JsonNode = if (dto is String) mapper.readTree(dto) else mapper.valueToTree(dto)
        return mapper.writeValueAsString(normalise(tree))
    }

    /** [RequestFingerprint] of `method`, `path` and the [canonical] body. */
    fun of(objectMapper: ObjectMapper, method: String, path: String, dto: Any?): String =
        RequestFingerprint.of(method, path, canonical(objectMapper, dto))

    private fun canonicalCopy(source: ObjectMapper): ObjectMapper = source.copy()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)

    private fun normalise(node: JsonNode): JsonNode = when {
        node.isObject -> {
            val sorted = ObjectNode(JsonNodeFactory.instance)
            node.properties()
                .filterNot { it.value.isNull }
                .sortedBy { it.key }
                .forEach { sorted.set<JsonNode>(it.key, normalise(it.value)) }
            sorted
        }
        node.isArray -> ArrayNode(JsonNodeFactory.instance).also { out -> node.forEach { out.add(normalise(it)) } }
        node.isFloatingPointNumber -> JsonNodeFactory.instance.numberNode(node.decimalValue().stripTrailingZeros())
        else -> node
    }
}
