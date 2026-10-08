// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import java.security.MessageDigest
import java.util.UUID

/** An evidence identity, without the personal or financial data in the original event. */
data class AgentAuditEvidenceIdentity(val eventId: UUID, val payloadSha256: String)

/**
 * Compares independently collected source and destination identities. This is a pure check: it
 * neither queries a database nor marks a replay campaign reconciled. An approved operator must
 * still verify the source inventory, consumer offsets, dead letters, and destination hash chain.
 */
object AgentAuditDestinationReconciliation {
    data class Result(val count: Int, val identityManifestSha256: String)

    fun compare(source: List<AgentAuditEvidenceIdentity>, destination: List<AgentAuditEvidenceIdentity>): Result {
        check(source.isNotEmpty()) { "Audit source manifest is empty" }
        val sourceById = index(source, "source")
        val destinationById = index(destination, "destination")
        check(sourceById == destinationById) { "Audit destination identities or payload digests differ" }

        val digest = MessageDigest.getInstance("SHA-256")
        sourceById.entries.sortedBy { it.key.toString() }.forEach { (eventId, payloadSha256) ->
            digest.update("$eventId:$payloadSha256\n".toByteArray(Charsets.US_ASCII))
        }
        return Result(source.size, digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) })
    }

    private fun index(rows: List<AgentAuditEvidenceIdentity>, side: String): Map<UUID, String> {
        check(rows.all { it.payloadSha256.matches(SHA256) }) { "Audit $side manifest has an invalid digest" }
        val byId = rows.associate { it.eventId to it.payloadSha256 }
        check(byId.size == rows.size) { "Audit $side manifest contains duplicate event identities" }
        return byId
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private const val BYTE_MASK = 0xff
}
