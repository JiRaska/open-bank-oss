// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.domain.AuthorityEvidence
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class AuthorityEventDecoder(private val mapper: ObjectMapper) {
    fun decode(payload: String): AuthorityEvidence? {
        require(payload.length <= MAX_PAYLOAD) { "authority event exceeds the payload budget" }
        val root = mapper.readTree(payload)
        val type = root.requiredText("eventType")
        if (type in NON_LIFECYCLE_TYPES) return null
        require(type in TYPES) { "unsupported authority event" }
        val revision = root.path("lifecycleRevision")
        require(revision.isIntegralNumber && revision.canConvertToLong() && revision.asLong() >= 0) {
            "authority events require a nonnegative lifecycleRevision"
        }
        val values = capabilities(root)
        val active = type in ACTIVE_TYPES
        val resourceType = root.optionalText("resourceType")
        val resourceId = root.optionalText("resourceId")?.let(UUID::fromString)
        val policy = root.optionalText("approvalPolicy")
        val approvals = approvals(root)
        val validFrom = root.optionalText("validFrom")?.let(Instant::parse)
        val validTo = root.optionalText("validTo")?.let(Instant::parse)
        require(!active || resourceType != null && resourceId != null && validFrom != null && values.isNotEmpty()) {
            "active authority evidence is incomplete"
        }
        require((resourceType == null) == (resourceId == null)) {
            "resource type and identifier must be supplied together"
        }
        require(resourceType == null || resourceType.matches(SAFE_ENUM)) { "invalid resource type" }
        require(policy == null || policy.matches(SAFE_ENUM)) { "invalid approval policy" }
        require(validTo == null || validFrom == null || validTo > validFrom) { "invalid authority validity" }
        return AuthorityEvidence(
            UUID.fromString(root.requiredText("aggregateId")), revision.asLong(), type,
            UUID.fromString(root.requiredText("grantorPartyId")), UUID.fromString(root.requiredText("granteePartyId")),
            resourceType, resourceId, values, policy, approvals,
            validFrom, validTo, Instant.parse(root.requiredText("occurredAt")),
        )
    }

    private fun capabilities(root: JsonNode): List<String> {
        val capabilities = root.path("capabilities")
        require(capabilities.isMissingNode || capabilities.isArray && capabilities.size() <= MAX_CAPABILITIES) {
            "invalid authority capabilities"
        }
        return capabilities.map {
            require(it.isTextual && it.asText().matches(SAFE_ENUM)) { "invalid authority capability" }
            it.asText()
        }.distinct().sorted()
    }

    private fun approvals(root: JsonNode): Int? {
        val approvals = root.path("requiredApprovals")
        require(
            approvals.isMissingNode ||
                approvals.isNull ||
                approvals.isIntegralNumber &&
                approvals.canConvertToInt() &&
                approvals.asInt() in 1..MAX_APPROVALS,
        ) {
            "invalid required approvals"
        }
        return approvals.takeUnless { it.isMissingNode || it.isNull }?.asInt()
    }

    private fun JsonNode.requiredText(key: String): String = requireNotNull(optionalText(key)) { "missing $key" }
    private fun JsonNode.optionalText(key: String): String? {
        val value = path(key)
        if (value.isMissingNode || value.isNull) return null
        require(value.isTextual && value.asText().isNotBlank() && value.asText().length <= MAX_FIELD) { "invalid $key" }
        return value.asText()
    }

    private companion object {
        const val MAX_PAYLOAD = 32768
        const val MAX_FIELD = 100
        const val MAX_CAPABILITIES = 100
        const val MAX_APPROVALS = 100
        val SAFE_ENUM = Regex("[A-Z][A-Z0-9_]{0,79}")
        val ACTIVE_TYPES = setOf("DelegationActivated", "DelegationReinstated")
        val NON_LIFECYCLE_TYPES = setOf("SpendReserved", "SpendConfirmed", "SpendReleased")
        val TYPES = ACTIVE_TYPES + setOf(
            "DelegationOffered",
            "DelegationDeclined",
            "DelegationRevoked",
            "DelegationSuspended",
            "DelegationRenounced",
            "DelegationExpired",
        )
    }
}
