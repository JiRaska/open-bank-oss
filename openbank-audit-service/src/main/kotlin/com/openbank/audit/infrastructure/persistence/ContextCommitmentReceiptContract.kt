// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.infrastructure.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.domain.model.AuditEntry

private val receiptJson = jacksonObjectMapper()
private val sha256 = Regex("[0-9a-f]{64}")

/** Prevent a future caller from committing a receipt for different evidence. */
internal fun validateContextReceiptEntry(entry: AuditEntry, commitment: String) {
    require(entry.eventType == "CONTEXT_READ_AUDIT_COMMITTED")
    require(entry.aggregateType == "CONTEXT_READ_AUDIT")
    require(entry.sourceService == "context-service")
    require(entry.aggregateId == entry.id.toString())
    require(sha256.matches(commitment))
    val payload = receiptJson.readTree(entry.payload)
    require(payload.path("eventId").textValue() == entry.id.toString())
    require(payload.path("commitment").textValue() == commitment)
}
