// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/** A transport pointer. Its presence does not establish a fraud finding or customer link. */
data class FraudCaseReference(
    val eventId: UUID,
    val eventType: String,
    val caseId: UUID,
    val revision: Long,
    val occurredAt: Instant,
)

@ApplicationScoped
class FraudCaseReferenceDecoder(private val mapper: ObjectMapper) {
    fun decode(payload: String, eventId: String?, headerType: String?): FraudCaseReference {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Fraud case reference exceeds payload budget" }
        require(headerType in TYPES) { "unsupported Fraud case reference event" }
        val root = mapper.readTree(payload)
        require(root != null && root.isObject) { "Fraud case reference must be an object" }
        require(root.fieldNames().asSequence().toSet() == FIELDS) { "Fraud case reference contains unexpected fields" }
        require(root.path("eventType").isTextual && root.path("eventType").asText() == headerType) {
            "Fraud case reference event type disagrees with header"
        }
        val revision = root.path("revision")
        require(revision.isIntegralNumber && revision.canConvertToLong() && revision.asLong() > 0) {
            "invalid Fraud case revision"
        }
        require(
            (headerType == OPENED && revision.asLong() == 1L) ||
                (headerType == CLOSED && revision.asLong() == 2L),
        ) { "Fraud case lifecycle revision disagrees with event type" }
        val occurredAt = root.path("occurredAt")
        require(occurredAt.isTextual) { "Fraud case reference requires occurredAt" }
        return FraudCaseReference(
            eventId = uuid(requireNotNull(eventId)),
            eventType = headerType,
            caseId = uuid(root.path("caseId").asText()),
            revision = revision.asLong(),
            occurredAt = Instant.parse(occurredAt.asText()),
        )
    }

    private fun uuid(value: String): UUID {
        require(value.matches(UUID_PATTERN)) { "invalid Fraud case reference identifier" }
        return UUID.fromString(value)
    }

    companion object {
        const val OPENED = "fraud.case_opened"
        const val CLOSED = "fraud.case_closed"
        private const val MAX_BYTES = 512
        private val TYPES = setOf(OPENED, CLOSED)
        private val FIELDS = setOf("eventType", "caseId", "revision", "occurredAt")
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
