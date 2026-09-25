// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.context.infrastructure

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/** Transport pointer only; the approved relationship must later be checked at Lending. */
data class LendingGuaranteeReference(
    val eventId: UUID,
    val guaranteeId: UUID,
    val loanId: UUID,
    val revision: Long,
    val bankScope: String,
    val occurredAt: Instant,
)

@ApplicationScoped
class LendingGuaranteeReferenceDecoder(private val mapper: ObjectMapper) {
    fun decode(payload: String, eventId: String?, headerType: String?): LendingGuaranteeReference {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Lending reference exceeds payload budget" }
        require(headerType == TYPE) { "unexpected Lending reference type" }
        val parser = mapper.factory.createParser(payload).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        val root: JsonNode = parser.use {
            val parsed = mapper.readTree<JsonNode>(it)
            require(it.nextToken() == null) { "trailing Lending reference content" }
            parsed
        }
        require(root != null && root.isObject && root.fieldNames().asSequence().toSet() == FIELDS) {
            "Lending reference contains unexpected fields"
        }
        require(root.path("schemaVersion").isIntegralNumber && root.path("schemaVersion").asInt() == 1) {
            "unsupported Lending reference schema"
        }
        require(root.path("eventType").isTextual && root.path("eventType").asText() == TYPE) {
            "Lending reference type disagrees with header"
        }
        val revision = root.path("revision")
        require(revision.isIntegralNumber && revision.canConvertToLong() && revision.asLong() > 0) {
            "invalid Lending reference revision"
        }
        val scope = text(root.path("bankScope"))
        require(scope.matches(BANK_SCOPE)) { "invalid Lending bank scope" }
        return LendingGuaranteeReference(
            eventId = uuid(requireNotNull(eventId)),
            guaranteeId = uuid(text(root.path("guaranteeId"))),
            loanId = uuid(text(root.path("loanId"))),
            revision = revision.asLong(),
            bankScope = scope,
            occurredAt = Instant.parse(text(root.path("occurredAt"))),
        )
    }

    private fun text(node: com.fasterxml.jackson.databind.JsonNode): String {
        require(node.isTextual) { "Lending reference field must be text" }
        return node.asText()
    }

    private fun uuid(value: String): UUID {
        require(value.matches(UUID_PATTERN)) { "invalid Lending reference identifier" }
        return UUID.fromString(value)
    }

    companion object {
        const val TYPE = "lending.graph.guarantee.approved"
        private const val MAX_BYTES = 512
        private val FIELDS =
            setOf("schemaVersion", "eventType", "guaranteeId", "loanId", "revision", "bankScope", "occurredAt")
        private val BANK_SCOPE = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
