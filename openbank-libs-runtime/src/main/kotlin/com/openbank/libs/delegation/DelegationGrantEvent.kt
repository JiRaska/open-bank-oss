// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.delegation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One `openbank.delegation.events` record, decoded (ADR-0232 D3, ADR-0375 D2).
 *
 * Every service that enforces a delegation keeps a local projection fed by this stream, and until
 * this type existed each one decoded the envelope itself. Measured on 2026-11-12,
 * account-service and card-issuance-service held two copies whose first nine lines of parsing were
 * identical and whose event-type constants agreed exactly — benign that day, and the kind of
 * duplication whose failure is silent: rename a field in delegation-service, fix the copy that
 * someone happened to open, and the other projection stops matching while staying green. A grant
 * that stops matching does not fail loudly; it leaves a REVOKED grant enforceable, which is the
 * worst direction this projection can drift.
 *
 * The field set is the UNION of what the two consumers read, which costs a card projection nothing
 * — it ignores the approval and limit fields — and means the next enforcing service decodes with no
 * new code at all.
 */
data class DelegationGrantEvent(
    val type: String,
    val grantId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: String,
    val resourceId: UUID?,
    val capabilities: Set<String>,
    val validFrom: OffsetDateTime?,
    val validTo: OffsetDateTime?,
    val lifecycleRevision: Long?,
    val approvalPolicy: String?,
    val requiredApprovals: Int?,
    val approvalGroupId: UUID?,
    val approvalGroupRevision: Long?,
    val perTxLimitAmount: BigDecimal?,
    val perTxLimitCurrency: String?,
)

/**
 * The event types a projection acts on, and the two it must NOT.
 *
 * `DelegationOffered` and `DelegationDeclined` are emitted by delegation-service and deliberately
 * absent here: an offer that nobody has accepted must never create an enforceable row (ADR-0232 D4
 * makes acceptance a second SCA ceremony). Keeping the exclusion in one named place is the point —
 * as separate string literals in each consumer, "which types do we project?" had no single answer.
 */
object DelegationGrantEvents {
    const val ACTIVATED = "DelegationActivated"
    const val REINSTATED = "DelegationReinstated"
    const val SUSPENDED = "DelegationSuspended"
    const val REVOKED = "DelegationRevoked"
    const val EXPIRED = "DelegationExpired"
    const val RENOUNCED = "DelegationRenounced"

    /** A grant becomes, or stays, enforceable. */
    val ENABLING: Set<String> = setOf(ACTIVATED, REINSTATED)

    /** A grant stops being enforceable. Never swallowed: see [DelegationGrantEvent]. */
    val DISABLING: Set<String> = setOf(SUSPENDED, REVOKED, EXPIRED, RENOUNCED)

    /** Emitted upstream and projected by nobody, on purpose. */
    val IGNORED: Set<String> = setOf("DelegationOffered", "DelegationDeclined")

    fun isProjected(type: String): Boolean = type in ENABLING || type in DISABLING
}

/**
 * Decodes the envelope, returning `null` for anything it cannot use.
 *
 * `null` means POISON PILL, not "transient failure": the caller acks it rather than retrying, which
 * is why every field that the projection cannot work without is a hard requirement here and every
 * optional one degrades to null rather than failing the record. The three required ids are the
 * grant, its grantor and its grantee — without any of them there is nothing to key a row on.
 */
object DelegationGrantEventDecoder {

    fun decode(objectMapper: ObjectMapper, payload: String): DelegationGrantEvent? =
        runCatching { objectMapper.readTree(payload) }.getOrNull()?.let(::decode)

    fun decode(node: JsonNode): DelegationGrantEvent? {
        val grantId = node.uuid("aggregateId") ?: return null
        val granteePartyId = node.uuid("granteePartyId") ?: return null
        val grantorPartyId = node.uuid("grantorPartyId") ?: return null
        return DelegationGrantEvent(
            type = node.path("eventType").asText(""),
            grantId = grantId,
            grantorPartyId = grantorPartyId,
            granteePartyId = granteePartyId,
            resourceType = node.path("resourceType").asText(""),
            resourceId = node.uuid("resourceId"),
            capabilities = node.path("capabilities").takeIf { it.isArray }
                ?.mapNotNull { it.asText(null) }?.toSet() ?: emptySet(),
            validFrom = node.timestamp("validFrom"),
            validTo = node.timestamp("validTo"),
            lifecycleRevision = node.longOrNull("lifecycleRevision"),
            approvalPolicy = node.path("approvalPolicy").asText(null),
            requiredApprovals = node.path("requiredApprovals").takeIf { it.isIntegralNumber }?.intValue(),
            approvalGroupId = node.uuid("approvalGroupId"),
            approvalGroupRevision = node.longOrNull("approvalGroupRevision"),
            perTxLimitAmount = node.path("perTransactionLimit").path("amount").asText(null)?.toBigDecimalOrNull(),
            perTxLimitCurrency = node.path("perTransactionLimit").path("currency").asText(null),
        )
    }

    private fun JsonNode.uuid(field: String): UUID? =
        path(field).asText(null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun JsonNode.timestamp(field: String): OffsetDateTime? =
        path(field).asText(null)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    private fun JsonNode.longOrNull(field: String): Long? = path(field).takeIf { it.isIntegralNumber }?.longValue()
}
