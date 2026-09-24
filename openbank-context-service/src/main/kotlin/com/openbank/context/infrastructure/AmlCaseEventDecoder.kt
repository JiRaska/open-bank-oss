// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/** Minimized source observation: screening status does not establish financial crime. */
data class AmlCaseObservation(
    val eventId: UUID,
    val caseId: UUID,
    val partyId: UUID,
    val accountId: UUID?,
    val transactionId: UUID?,
    val eventType: String,
    val status: String,
    val previousStatus: String?,
    val riskLevel: String?,
    val screeningType: String?,
    val occurredAt: Instant,
)

@ApplicationScoped
class AmlCaseEventDecoder(private val mapper: ObjectMapper) {
    fun decode(payload: String, eventId: String?, eventType: String?): AmlCaseObservation {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD) { "AML event exceeds payload budget" }
        val identity = uuid(requireNotNull(eventId) { "missing eventId header" })
        require(eventType in TYPES) { "missing or unsupported ce-type header" }
        val root = mapper.readTree(payload)
        require(root != null && root.isObject) { "AML event must be an object" }
        val created = eventType == CREATED
        val status = root.enumValue(if (created) "status" else "newStatus", STATUSES)
        val risk = if (created) root.enumValue("riskLevel", RISKS) else null
        if (created) {
            val expected = if (risk in setOf("HIGH", "CRITICAL")) "UNDER_REVIEW" else "OPEN"
            require(status == expected) { "created AML status conflicts with its source risk" }
        }
        return AmlCaseObservation(
            identity,
            uuid(root.requiredText("caseId")),
            uuid(root.requiredText("partyId")),
            if (created) root.optionalText("accountId")?.let(::uuid) else null,
            if (created) root.optionalText("transactionId")?.let(::uuid) else null,
            requireNotNull(eventType),
            status,
            if (created) null else root.enumValue("previousStatus", STATUSES),
            risk,
            if (created) root.enumValue("screeningType", SCREENINGS) else null,
            Instant.parse(root.requiredText("occurredAt")),
        )
    }

    private fun uuid(value: String): UUID {
        require(value.matches(UUID_PATTERN)) { "invalid AML identifier" }
        return UUID.fromString(value)
    }

    private fun JsonNode.enumValue(field: String, allowed: Set<String>): String = requiredText(field).also {
        require(it in allowed) { "invalid $field" }
    }

    private fun JsonNode.requiredText(field: String): String = requireNotNull(optionalText(field)) { "missing $field" }

    private fun JsonNode.optionalText(field: String): String? {
        val value = path(field)
        if (value.isMissingNode || value.isNull) return null
        require(value.isTextual && value.asText().isNotBlank() && value.asText().length <= MAX_FIELD) {
            "invalid $field"
        }
        return value.asText()
    }

    private companion object {
        const val MAX_PAYLOAD = 32768
        const val MAX_FIELD = 100
        const val CREATED = "aml.case.created.v1"
        val TYPES = setOf(CREATED, "aml.case.status_changed.v1")
        val STATUSES = setOf("OPEN", "UNDER_REVIEW", "CLEARED", "BLOCKED", "ESCALATED")
        val RISKS = setOf("LOW", "MEDIUM", "HIGH", "CRITICAL")
        val SCREENINGS = setOf(
            "CUSTOMER_ONBOARDING",
            "TRANSACTION_MONITORING",
            "PERIODIC_REVIEW",
            "MANUAL_INVESTIGATION",
        )
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
