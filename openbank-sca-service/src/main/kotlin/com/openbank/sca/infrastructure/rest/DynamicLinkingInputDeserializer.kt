// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.sca.domain.model.DynamicLinkingData

/** Accepts the long-served object and the v1 schema's JSON-serialized object string. */
class DynamicLinkingInputDeserializer : JsonDeserializer<DynamicLinkingData>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): DynamicLinkingData {
        val mapper = parser.codec as ObjectMapper
        val input = mapper.readTree<JsonNode>(parser)
        val objectNode = if (input.isTextual) {
            try {
                mapper.readTree(input.asText())
            } catch (ex: JsonProcessingException) {
                throw JsonMappingException.from(parser, "dynamicLinkingData string must contain a JSON object", ex)
            }
        } else {
            input
        }
        if (objectNode == null || !objectNode.isObject) {
            throw JsonMappingException.from(parser, "dynamicLinkingData must be a JSON object")
        }
        return mapper.treeToValue(objectNode, DynamicLinkingData::class.java)
    }
}
