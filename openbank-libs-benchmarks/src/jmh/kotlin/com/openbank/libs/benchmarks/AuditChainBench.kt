// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.audit.AuditChain
import com.openbank.libs.audit.AuditChainLink
import com.openbank.libs.audit.AuditChainVerification
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.HashLinkedAuditEnvelope
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** ADR-0323 producer-side link, and a verifier pass over a 1 000-envelope chain. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class AuditChainBench {
    private val event = event(0)
    private val head = AuditChainLink(PRODUCER, HEAD_SEQ, AuditChain.GENESIS_HASH, AuditChain.sha256Hex("head"))
    private val chain: List<Map<String, Any?>> = buildChain()

    @Benchmark
    fun link(): HashLinkedAuditEnvelope = AuditChain.link(event, PRODUCER, head)

    @Benchmark
    fun verify1k(): AuditChainVerification {
        val result = AuditChain.verify(chain)
        check(result.intact) { "benchmark chain must verify: ${result.reason}" }
        return result
    }

    private companion object {
        const val PRODUCER = "lending"
        const val HEAD_SEQ = 41L
        const val CHAIN_LENGTH = 1000

        fun event(i: Int) = AuditEvent(
            eventId = UUID(0L, i.toLong()),
            actorId = "operator-$i",
            actorType = "USER",
            operation = "APPROVE",
            resourceType = "LOAN",
            resourceId = "loan-$i",
            timestamp = Instant.parse("2026-09-28T12:00:00Z"),
            traceId = "trace-$i",
            payload = mapOf("amount" to "1200.50", "currency" to "EUR"),
        )

        /** Decoded exactly as a verifier receives them: parsed back from the wire JSON. */
        fun buildChain(): List<Map<String, Any?>> {
            val json = ObjectMapper()
            var head: AuditChainLink? = null
            return List(CHAIN_LENGTH) { i ->
                val envelope = AuditChain.link(event(i), PRODUCER, head)
                head = envelope.link
                json.readValue(envelope.canonicalJson, Map::class.java).entries
                    .associate { (key, value) -> key.toString() to value }
            }
        }
    }
}
