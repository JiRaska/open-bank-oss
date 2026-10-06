// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** The broker carries a pointer only; the mapped owner finding remains inside KYB. */
data class KybObservationReference(
    val eventType: String,
    val eventId: UUID,
    val caseId: UUID,
    val observationId: UUID,
    val revision: Long,
    val sourceSha256: String,
)

@ApplicationScoped
class KybObservationReferenceDecoder(private val mapper: ObjectMapper) {
    fun decode(payload: String, eventId: String?, eventType: String?): KybObservationReference {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "KYB reference exceeds payload budget"
        }
        require(eventType in EVENT_TYPES) { "unsupported KYB reference event" }
        val root = mapper.readTree(payload)
        require(root != null && root.isObject) { "KYB reference must be an object" }
        require(root.fieldNames().asSequence().toSet() == FIELDS) { "KYB reference contains unexpected fields" }
        require(root.path("schemaVersion").isIntegralNumber && root.path("schemaVersion").asInt() == 1) {
            "unsupported KYB reference schema"
        }
        require(root.path("eventType").isTextual && root.path("eventType").asText() == eventType) {
            "KYB reference event type disagrees with header"
        }
        val revision = root.path("revision")
        require(revision.isIntegralNumber && revision.canConvertToLong() && revision.asLong() > 0) {
            "invalid KYB observation revision"
        }
        val hash = root.path("sourceSha256")
        require(hash.isTextual && hash.asText().matches(SHA256)) { "invalid KYB source hash" }
        return KybObservationReference(
            eventType = eventType,
            eventId = parseUuid(requireNotNull(eventId)),
            caseId = parseUuid(root.path("caseId").asText()),
            observationId = parseUuid(root.path("observationId").asText()),
            revision = revision.asLong(),
            sourceSha256 = hash.asText(),
        )
    }

    private fun parseUuid(value: String) = UUID.fromString(
        value.also {
            require(it.matches(UUID_PATTERN)) { "invalid KYB reference identifier" }
        },
    )

    companion object {
        const val EVENT_TYPE = "KybUboObservationRecorded"
        const val RESTRICTED_EVENT_TYPE = "KybUboObservationRestricted"
        private val EVENT_TYPES = setOf(EVENT_TYPE, RESTRICTED_EVENT_TYPE)
        private const val MAX_PAYLOAD_BYTES = 1024
        private val FIELDS = setOf("schemaVersion", "eventType", "caseId", "observationId", "revision", "sourceSha256")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
