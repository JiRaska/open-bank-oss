// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.reference

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.treasury.domain.reference.BookPayload
import com.openbank.treasury.domain.reference.BucketSetPayload
import com.openbank.treasury.domain.reference.CalendarPayload
import com.openbank.treasury.domain.reference.CounterpartyPayload
import com.openbank.treasury.domain.reference.IndexPayload
import com.openbank.treasury.domain.reference.NettingPayload
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferencePayload
import com.openbank.treasury.domain.reference.RegulatoryCodePayload
import com.openbank.treasury.domain.reference.SecurityPayload
import com.openbank.treasury.domain.reference.SsiPayload
import jakarta.enterprise.context.ApplicationScoped

/**
 * The kind decides the payload type (the path names the kind, the body is its payload). Unknown
 * fields are refused, so a misspelt field is a 400 rather than a silently ignored setting.
 */
@ApplicationScoped
class ReferencePayloadCodec(objectMapper: ObjectMapper) {
    private val writer: ObjectMapper = objectMapper.copy().addMixIn(
        ReferencePayload::class.java,
        PayloadMixIn::class.java,
    )
    private val strict: ObjectMapper = writer.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    /** `recordKind` is derived from the type, never part of the stored or sent JSON. */
    @JsonIgnoreProperties("recordKind")
    private abstract class PayloadMixIn

    fun read(kind: ReferenceKind, node: JsonNode): ReferencePayload {
        require(node.isObject) { "payload must be a JSON object" }
        return try {
            strict.treeToValue(node, typeOf(kind))
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            throw IllegalArgumentException("invalid ${kind.wire} payload: ${e.originalMessage}", e)
        }
    }

    fun read(kind: ReferenceKind, json: String): ReferencePayload = read(kind, strict.readTree(json))

    fun write(payload: ReferencePayload): String = writer.writeValueAsString(payload)

    fun tree(payload: ReferencePayload): JsonNode = writer.valueToTree(payload)

    private fun typeOf(kind: ReferenceKind): Class<out ReferencePayload> = when (kind) {
        ReferenceKind.CALENDAR -> CalendarPayload::class.java
        ReferenceKind.INDEX -> IndexPayload::class.java
        ReferenceKind.COUNTERPARTY -> CounterpartyPayload::class.java
        ReferenceKind.SSI -> SsiPayload::class.java
        ReferenceKind.NETTING -> NettingPayload::class.java
        ReferenceKind.SECURITY -> SecurityPayload::class.java
        ReferenceKind.BOOK -> BookPayload::class.java
        ReferenceKind.BUCKET_SET -> BucketSetPayload::class.java
        ReferenceKind.REGULATORY_CODE -> RegulatoryCodePayload::class.java
    }
}
