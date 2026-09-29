// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Default
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class HashLinkedOutboxAuditEventPublisherTest {

    private val mapper = jacksonObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    /**
     * In-memory port honouring the AuditChainOutbox contract: its own lock (auto-commit per call)
     * serialises read-head + insert. `yield()` between read and write so a port WITHOUT that lock
     * would fork the chain.
     */
    private class InMemoryOutbox : AuditChainOutbox {
        val rows = mutableListOf<AuditOutboxRecord>()
        private val dbLock = Mutex()
        override suspend fun append(producer: String, build: (head: AuditChainLink?) -> AuditOutboxRecord) {
            dbLock.withLock {
                val head = rows.lastOrNull { it.link.producer == producer }?.link
                yield()
                rows += build(head)
            }
        }
    }

    /** The current business transaction of a coroutine. */
    private class Tx(val name: String) : AbstractCoroutineContextElement(Tx) {
        companion object Key : CoroutineContext.Key<Tx>
    }

    /**
     * Models Postgres: the per-producer lock is taken on first append of a transaction, is
     * re-entrant within it, and is released only at [commit] — not when append returns.
     */
    private class TransactionalOutbox : AuditChainOutbox {
        val rows = mutableListOf<AuditOutboxRecord>()
        private val dbLock = Mutex()
        private var owner: Tx? = null
        override suspend fun append(producer: String, build: (head: AuditChainLink?) -> AuditOutboxRecord) {
            val tx = checkNotNull(coroutineContext[Tx]) { "no transaction" }
            if (owner !== tx) {
                dbLock.lock()
                owner = tx
            }
            rows += build(rows.lastOrNull()?.link)
        }
        fun commit(tx: Tx) {
            if (owner === tx) {
                owner = null
                dbLock.unlock()
            }
        }
    }

    private fun event(n: Int) = AuditEvent(
        actorId = "party-$n",
        actorType = "CUSTOMER",
        operation = "account.updated",
        resourceType = "ACCOUNT",
        resourceId = "acc-$n",
        timestamp = Instant.parse("2026-09-26T10:00:00Z"),
        traceId = "trace-$n",
        channel = AuditChannel.API,
        actChain = listOf("agent-1"),
        sessionId = "s-1",
        payload = mapOf(
            "amount" to BigDecimal("12.50"),
            "note" to "line\n\"quoted\"",
            "nested" to mapOf("b" to 1, "a" to null),
        ),
    )

    private fun decoded(rows: List<AuditOutboxRecord>): List<Map<String, Any?>> =
        rows.map { mapper.readValue<Map<String, Any?>>(it.payload) }

    private suspend fun publishN(n: Int): InMemoryOutbox {
        val outbox = InMemoryOutbox()
        val publisher = HashLinkedOutboxAuditEventPublisher(outbox, "party-service")
        (1..n).forEach { publisher.publish(event(it)) }
        return outbox
    }

    @Test
    fun `links events into an intact chain that verifies from the wire bytes`(): Unit = runBlocking {
        val rows = publishN(3).rows
        assertThat(rows.map { it.link.seq }).containsExactly(1L, 2L, 3L)
        assertThat(rows[0].link.prevHash).isEqualTo(AuditChain.GENESIS_HASH)
        assertThat(rows[1].link.prevHash).isEqualTo(rows[0].link.hash)
        assertThat(rows.map { it.aggregateId }.toSet()).hasSize(1)
        assertThat(AuditChain.verify(decoded(rows)).intact).isTrue()
    }

    @Test
    fun `tampering with any field breaks verification at that envelope`(): Unit = runBlocking {
        val envs = decoded(publishN(3).rows).map { it.toMutableMap() }
        envs[1]["actorId"] = "someone-else"
        val result = AuditChain.verify(envs)
        assertThat(result.intact).isFalse()
        assertThat(result.firstBrokenIndex).isEqualTo(1)
        assertThat(result.reason).isEqualTo("hash mismatch")
    }

    @Test
    fun `tampering inside the nested payload is detected`(): Unit = runBlocking {
        val envs = decoded(publishN(2).rows).map { it.toMutableMap() }
        envs[0]["payload"] =
            mapOf("amount" to BigDecimal("99.00"), "note" to "x", "nested" to mapOf("b" to 1, "a" to null))
        assertThat(AuditChain.verify(envs).firstBrokenIndex).isEqualTo(0)
    }

    @Test
    fun `a dropped event is detected as a seq gap`(): Unit = runBlocking {
        val envs = decoded(publishN(3).rows)
        val result = AuditChain.verify(listOf(envs[0], envs[2]))
        assertThat(result.intact).isFalse()
        assertThat(result.firstBrokenIndex).isEqualTo(1)
        assertThat(result.reason).isEqualTo("seq 3, expected 2")
    }

    @Test
    fun `a re-hashed forged envelope still breaks the prevHash link of its successor`(): Unit = runBlocking {
        val envs = decoded(publishN(3).rows).map { it.toMutableMap() }
        envs[1]["actorId"] = "forger"
        envs[1]["hash"] = AuditChain.sha256Hex(CanonicalJson.write(envs[1] - AuditChain.HASH_FIELD))
        val result = AuditChain.verify(envs)
        assertThat(result.firstBrokenIndex).isEqualTo(2)
        assertThat(result.reason).isEqualTo("prevHash does not match previous hash")
    }

    @Test
    fun `concurrent publishes are serialised - no forked chain`(): Unit = runBlocking {
        val outbox = InMemoryOutbox()
        val publisher = HashLinkedOutboxAuditEventPublisher(outbox, "party-service")
        coroutineScope { (1..20).map { async { publisher.publish(event(it)) } }.awaitAll() }
        assertThat(outbox.rows.map { it.link.seq }).isEqualTo((1L..20L).toList())
        assertThat(AuditChain.verify(decoded(outbox.rows)).intact).isTrue()
    }

    @Test
    fun `wire payload keeps every key the audit-service consumer reads (additive only)`(): Unit = runBlocking {
        val env = decoded(publishN(1).rows).single()
        assertThat(env)
            .containsEntry("eventId", env["eventId"])
            .containsEntry("eventType", "account.updated")
            .containsEntry("aggregateType", "ACCOUNT")
            .containsEntry("aggregateId", "acc-1")
            .containsEntry("actorId", "party-1")
            .containsEntry("actorType", "CUSTOMER")
            .containsEntry("sourceService", "party-service")
            .containsEntry("correlationId", "trace-1")
            .containsEntry("occurredAt", "2026-09-26T10:00:00Z")
            .containsEntry("channel", "api")
            .containsEntry("actChain", listOf("agent-1"))
            .containsEntry("sessionId", "s-1")
            .containsEntry("producer", "party-service")
            .containsKeys("seq", "prevHash", "hash")
    }

    @Test
    fun `canonical json sorts keys and has no whitespace`() {
        assertThat(CanonicalJson.write(mapOf("b" to 1, "a" to listOf(true, null), "c" to BigDecimal("1.50"))))
            .isEqualTo("""{"a":[true,null],"b":1,"c":1.50}""")
    }

    @Test
    fun `a decimal amount keeps its scale on the wire and still verifies`(): Unit = runBlocking {
        val rows = publishN(1).rows
        assertThat(rows.single().payload).contains(""""amount":12.50""")
        assertThat(AuditChain.verify(decoded(rows)).intact).isTrue()
    }

    @Test
    fun `two events in one transaction while another publisher waits do not deadlock`(): Unit = runBlocking {
        val outbox = TransactionalOutbox()
        val publisher = HashLinkedOutboxAuditEventPublisher(outbox, "party-service")
        val txA = Tx("A")
        val txB = Tx("B")
        withTimeout(2_000) {
            withContext(txA) { publisher.publish(event(1)) } // A now holds the DB lock until commit
            val b = launch(txB) {
                publisher.publish(event(2)) // blocks on A's DB lock
                outbox.commit(txB)
            }
            yield() // let B run up to the DB lock
            withContext(txA) { publisher.publish(event(3)) } // second event of the SAME transaction
            outbox.commit(txA)
            b.join()
        }
        assertThat(outbox.rows.map { it.link.seq }).containsExactly(1L, 2L, 3L)
        assertThat(AuditChain.verify(decoded(outbox.rows)).intact).isTrue()
    }

    @Test
    fun `publisher is opt-in - the logging publisher stays the default`() {
        val cls = HashLinkedOutboxAuditEventPublisher::class.java
        assertThat(cls.isAnnotationPresent(Alternative::class.java)).isTrue()
        val gate = cls.getAnnotation(IfBuildProperty::class.java)
        assertThat(gate.name).isEqualTo("openbank.audit.publisher")
        assertThat(gate.stringValue).isEqualTo("hash-linked-outbox")
        assertThat(gate.enableIfMissing).isFalse()
        assertThat(LoggingAuditEventPublisher::class.java.isAnnotationPresent(Default::class.java)).isTrue()
    }
}
