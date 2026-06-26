// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.simulation.adapters

import java.security.MessageDigest

/** One hash-chained audit record (mirrors the `openbank-libs` audit chain). */
data class AuditRecord(val sequence: Long, val event: String, val previousHash: String, val hash: String)

/**
 * An append-only, hash-chained audit log: each record's hash covers its payload AND the
 * previous record's hash, so any tampering or gap breaks the chain. Backs the ADR-0100
 * "audit completeness" invariant — every money-path state transition must appear here and the
 * chain must verify.
 */
class AuditLog {

    private val records = mutableListOf<AuditRecord>()

    fun append(event: String) {
        val previousHash = records.lastOrNull()?.hash ?: GENESIS
        val sequence = records.size.toLong()
        records.add(AuditRecord(sequence, event, previousHash, chainHash(sequence, event, previousHash)))
    }

    fun size(): Int = records.size

    fun events(): List<String> = records.map { it.event }

    /** Verify the chain links and the contiguous sequence numbers. */
    fun verifyChain(): Boolean {
        var previousHash = GENESIS
        records.forEachIndexed { index, record ->
            if (record.sequence != index.toLong()) return false
            if (record.previousHash != previousHash) return false
            if (record.hash != chainHash(record.sequence, record.event, record.previousHash)) return false
            previousHash = record.hash
        }
        return true
    }

    private fun chainHash(sequence: Long, event: String, previousHash: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$sequence|$event|$previousHash".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val GENESIS = "GENESIS"
    }
}
