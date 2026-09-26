// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.signing

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.openbank.delegation.application.usecase.SigningPayloadCodec
import jakarta.enterprise.context.ApplicationScoped

/**
 * Canonical JSON: map keys sorted at every depth, decimals kept as written (no double rounding),
 * no insignificant whitespace. The same frozen payload therefore always hashes to the same
 * SHA-256, which is what a signer's SCA challenge is dynamically linked to.
 */
@ApplicationScoped
class JacksonSigningPayloadCodec(objectMapper: ObjectMapper) : SigningPayloadCodec {

    private val mapper: ObjectMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(SerializationFeature.INDENT_OUTPUT, false)
        .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)

    override fun canonical(value: Any?): String {
        // Round-trip through plain maps so nested objects — whatever their origin — sort by key.
        val plain: Any? = mapper.convertValue(value, Any::class.java)
        return mapper.writeValueAsString(plain)
    }

    override fun parseObject(json: String): Map<String, Any?> = mapper.readValue(json, MAP)

    private companion object {
        val MAP = object : TypeReference<Map<String, Any?>>() {}
    }
}
