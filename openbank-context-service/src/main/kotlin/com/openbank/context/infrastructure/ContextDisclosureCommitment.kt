// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A versioned commitment to the complete restricted local disclosure row. */
internal object ContextDisclosureCommitment {
    const val SCHEMA_VERSION = 1

    fun of(row: ContextDisclosureAuditEntity): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(SCHEMA_VERSION)
            listOf(
                row.id.toString(),
                row.decisionAuditId.toString(),
                row.bankScope,
                row.queryHash,
                row.projectionGeneration?.toString(),
                row.evidenceRefsJson,
                row.evidenceCount.toString(),
                row.truncated.toString(),
                row.occurredAt.toString(),
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
