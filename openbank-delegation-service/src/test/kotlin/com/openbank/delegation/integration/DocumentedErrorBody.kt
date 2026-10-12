// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import org.assertj.core.api.Assertions.assertThat
import org.yaml.snakeyaml.Yaml

/** Compare an HTTP error with the schema reached by its published path, status and media type. */
internal object DocumentedErrorBody {
    @Suppress("UNCHECKED_CAST")
    private val spec: Map<String, Any?> by lazy {
        requireNotNull(javaClass.getResourceAsStream("/openapi.yaml"))
            .use { Yaml().load<Map<String, Any?>>(it) }
    }

    @Suppress("UNCHECKED_CAST")
    fun assertConforms(path: String, status: Int, mediaType: String, body: Map<String, Any?>) {
        val components = spec["components"] as Map<String, Any?>
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path)["post"] as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val response = resolve(responses.getValue(status.toString()), components, "responses")
        val content = response["content"] as Map<String, Map<String, Any?>>
        val schema = resolve(content.getValue(mediaType)["schema"] as Map<String, Any?>, components, "schemas")
        val required = schema["required"] as List<String>
        val properties = schema["properties"] as Map<String, Map<String, Any?>>
        assertThat(body.keys).containsAll(required)
        for ((name, value) in body) {
            val property = properties[name] ?: continue
            when (property["type"]) {
                "string" -> assertThat(value).describedAs("$name type").isInstanceOf(String::class.java)
                "integer" -> assertThat(value).describedAs("$name type").isInstanceOf(Number::class.java)
            }
            (property["enum"] as? List<*>)?.let { assertThat(it).describedAs("$name enum").contains(value) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolve(node: Map<String, Any?>, components: Map<String, Any?>, section: String): Map<String, Any?> {
        val ref = node["\$ref"] as? String ?: return node
        val entries = components[section] as Map<String, Map<String, Any?>>
        return entries.getValue(ref.substringAfterLast('/'))
    }
}
