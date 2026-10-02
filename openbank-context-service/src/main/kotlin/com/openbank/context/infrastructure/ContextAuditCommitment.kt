// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Versioned, length-delimited commitment to the local restricted row. No row fields go to Kafka. */
internal object ContextAuditCommitment {
    const val SCHEMA_VERSION = 1

    fun of(row: ContextReadAuditEntity): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(SCHEMA_VERSION)
            listOf(
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
            ).forEach { value ->
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
