// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The producer-side link of an audit event (ADR-0323): which service emitted it, its position
 * in that service's chain, the previous link's hash and its own.
 */
data class AuditChainLink(val producer: String, val seq: Long, val prevHash: String, val hash: String)

/** One hash-linked envelope: the canonical JSON that goes on the wire, plus its link. */
data class HashLinkedAuditEnvelope(val event: AuditEvent, val link: AuditChainLink, val canonicalJson: String)

/**
 * Builds and verifies ADR-0323 envelopes.
 *
 * The wire payload IS the canonical JSON (sorted keys, no whitespace), so a verifier recomputes
 * the hash from the exact bytes it received — there is no second serialisation that could
 * disagree with the one that was hashed.
 *
 * Wire keys are ADDITIVE over what `openbank-audit-service`'s `AuditConsumer` reads today:
 * `eventId`, `eventType`, `aggregateType`, `aggregateId`, `actorId`, `actorType`,
 * `sourceService`, `correlationId`, `occurredAt`, `channel`, `actChain`, `sessionId` keep their
 * meaning; `producer`, `seq`, `prevHash`, `hash` are new.
 */
object AuditChain {
    const val GENESIS_HASH: String = "0000000000000000000000000000000000000000000000000000000000000000"
    const val HASH_FIELD: String = "hash"

    /** Build the next envelope after [head] (null = first event of this producer). */
    fun link(event: AuditEvent, producer: String, head: AuditChainLink?): HashLinkedAuditEnvelope {
        require(producer.isNotBlank()) { "producer must not be blank" }
        require(head == null || head.producer == producer) {
            "chain head belongs to '${head?.producer}', not '$producer'"
        }
        val seq = (head?.seq ?: 0L) + 1
        val prevHash = head?.hash ?: GENESIS_HASH
        val unhashed = fields(event, producer, seq, prevHash)
        val hash = sha256Hex(CanonicalJson.write(unhashed))
        val json = CanonicalJson.write(unhashed + (HASH_FIELD to hash))
        return HashLinkedAuditEnvelope(event, AuditChainLink(producer, seq, prevHash, hash), json)
    }

    /**
     * Verify one producer's envelopes in arrival order, given as decoded key/value maps (the
     * verifier reads what arrived, never a re-derived [AuditEvent]). Reports the FIRST broken
     * link: an altered envelope (hash mismatch), a dropped/duplicated/reordered one (seq gap) or
     * a spliced one (prevHash mismatch).
     */
    fun verify(envelopes: List<Map<String, Any?>>): AuditChainVerification {
        var expectedSeq = 1L
        var expectedPrev = GENESIS_HASH
        envelopes.forEachIndexed { index, env ->
            val claimed = env[HASH_FIELD] as? String
            val recomputed = sha256Hex(CanonicalJson.write(env - HASH_FIELD))
            val seq = (env["seq"] as? Number)?.toLong()
            val failure = when {
                claimed != recomputed -> "hash mismatch"
                seq != expectedSeq -> "seq $seq, expected $expectedSeq"
                env["prevHash"] != expectedPrev -> "prevHash does not match previous hash"
                else -> null
            }
            if (failure != null) return AuditChainVerification(false, index, failure)
            expectedSeq++
            expectedPrev = claimed!!
        }
        return AuditChainVerification(true, null, null)
    }

    internal fun fields(event: AuditEvent, producer: String, seq: Long, prevHash: String): Map<String, Any?> = mapOf(
        "eventId" to event.eventId.toString(),
        // Consumer-compatible aliases (AuditConsumer reads these keys).
        "eventType" to event.operation,
        "aggregateType" to event.resourceType,
        "aggregateId" to (event.resourceId ?: event.actorId),
        "actorId" to event.actorId,
        "actorType" to event.actorType,
        "sourceService" to producer,
        "correlationId" to event.traceId,
        "occurredAt" to event.timestamp.toString(),
        "channel" to event.channel,
        "actChain" to event.actChain,
        "sessionId" to event.sessionId,
        // The envelope's own fields, verbatim.
        "operation" to event.operation,
        "resourceType" to event.resourceType,
        "resourceId" to event.resourceId,
        "result" to event.result.name,
        "traceId" to event.traceId,
        "ipAddress" to event.ipAddress,
        "userAgent" to event.userAgent,
        "payload" to event.payload,
        // ADR-0323 additive integrity fields.
        "producer" to producer,
        "seq" to seq,
        "prevHash" to prevHash,
    )

    fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

data class AuditChainVerification(
    val intact: Boolean,
    /** Index of the first broken envelope, or null when intact. */
    val firstBrokenIndex: Int?,
    val reason: String?,
)

/**
 * Minimal canonical JSON writer: object keys sorted by code point, no whitespace, nulls kept.
 * Pure Kotlin so the domain module stays framework-free (ADR-0122).
 */
object CanonicalJson {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> appendString(sb, value)
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Number -> appendDecimal(sb, value)
            is Map<*, *> -> appendObject(sb, value)
            is Iterable<*> -> appendArray(sb, value)
            is Array<*> -> appendArray(sb, value.asIterable())
            is Instant, is UUID, is Enum<*> -> appendString(sb, value.toString())
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendDecimal(sb: StringBuilder, value: Number) {
        val d = value.toDouble()
        require(d.isFinite()) { "non-finite number is not valid JSON" }
        sb.append(java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString())
    }

    private fun appendObject(sb: StringBuilder, map: Map<*, *>) {
        sb.append('{')
        map.entries.map { it.key.toString() to it.value }.sortedBy { it.first }
            .forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(',')
                appendString(sb, k)
                sb.append(':')
                append(sb, v)
            }
        sb.append('}')
    }

    private fun appendArray(sb: StringBuilder, items: Iterable<*>) {
        sb.append('[')
        items.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            append(sb, v)
        }
        sb.append(']')
    }

    @Suppress("MagicNumber")
    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
