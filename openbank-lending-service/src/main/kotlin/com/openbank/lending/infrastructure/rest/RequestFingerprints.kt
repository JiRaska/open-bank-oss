// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.openbank.libs.idempotency.RequestFingerprint

/**
 * The canonical request fingerprint an `Idempotency-Key` is bound to (#10916) — the one helper
 * both lending intake endpoints share. The DESERIALISED DTO is re-serialised with sorted keys, so
 * whitespace and JSON key order in the raw body do not change the hash, while any change in a
 * field value does. The sorted-key mapper is derived from the service's own [ObjectMapper] once
 * per mapper instance, not per request.
 */
internal object RequestFingerprints {
    @Volatile
    private var cache: Pair<ObjectMapper, ObjectMapper>? = null

    fun of(objectMapper: ObjectMapper, method: String, path: String, body: Any): String =
        RequestFingerprint.of(method, path, canonical(objectMapper).writeValueAsString(body))

    private fun canonical(objectMapper: ObjectMapper): ObjectMapper {
        cache?.let { (source, canonical) -> if (source === objectMapper) return canonical }
        val canonical = objectMapper.copy()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        cache = objectMapper to canonical
        return canonical
    }
}
