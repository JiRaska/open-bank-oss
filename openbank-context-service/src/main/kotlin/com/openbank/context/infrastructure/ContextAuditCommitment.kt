// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Length-delimited commitment to the restricted local row. The Kafka envelope remains schema v1;
 * access-decision rows retain canonical hash v1, and disclosure-outcome rows use canonical hash v2.
 * Old commitments remain reproducible after the schema expansion. No row fields go to Kafka.
 */
internal object ContextAuditCommitment {
    const val SCHEMA_VERSION = 1
    private const val DISCLOSURE_CANONICAL_VERSION = 2

    fun of(row: ContextReadAuditEntity): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(if (row.accessAuditId == null) SCHEMA_VERSION else DISCLOSURE_CANONICAL_VERSION)
            val baseFields = listOf(
                row.id.toString(),
                row.bankScope,
                row.principalId,
                row.caseId,
                row.purpose,
                row.action,
                row.rootRef,
                row.decision,
                row.policyVersion,
                row.reasonCode,
                row.occurredAt.toString(),
                row.effectiveAt?.toString(),
                row.knownAt?.toString(),
            )
            val disclosureFields = if (row.accessAuditId == null) {
                emptyList()
            } else {
                listOf(
                    row.accessAuditId.toString(),
                    row.queryHash,
                    row.projectionGeneration,
                    row.evidenceRefsJson,
                    row.evidenceCount?.toString(),
                    row.responseTruncated?.toString(),
                    row.responseStatus?.toString(),
                )
            }
            (baseFields + disclosureFields).forEach { value ->
                if (value == null) {
                    out.writeInt(-1)
                } else {
                    val encoded = value.toByteArray(StandardCharsets.UTF_8)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
