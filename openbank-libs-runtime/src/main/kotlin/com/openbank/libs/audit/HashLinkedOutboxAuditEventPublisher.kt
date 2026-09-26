// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/** One row for the producer's audit outbox (ADR-0323). */
data class AuditOutboxRecord(
    val eventId: UUID,
    /** Name-based UUID of the producer: every event of one producer shares a Kafka partition. */
    val aggregateId: UUID,
    val eventType: String,
    /** The canonical JSON envelope, byte-for-byte what was hashed. */
    val payload: String,
    val link: AuditChainLink,
)

/**
 * Service-supplied port behind [HashLinkedOutboxAuditEventPublisher].
 *
 * The implementation MUST, in ONE database transaction that also holds a per-producer lock
 * (a Postgres advisory lock or `SELECT ... FOR UPDATE` on the head row): read the current chain
 * head, call [build] with it, and insert the returned record into the outbox. That lock is the
 * ONLY serialisation: the publisher deliberately holds no in-JVM lock of its own, because the DB
 * lock is held until the business transaction commits — a JVM mutex taken around it deadlocks
 * the moment one transaction emits two audit events while another coroutine is waiting (the
 * waiter holds the mutex and blocks on the DB lock; the owner blocks on the mutex). The lock must
 * therefore be re-entrant within one transaction (both advisory xact locks and `FOR UPDATE` are).
 * The record should join the caller's business transaction where one is active, so the audit
 * event commits atomically with the change it describes; acquire it as late as possible in that
 * transaction (ADR-0323), since every other audit write of the producer waits until it commits.
 */
interface AuditChainOutbox {
    suspend fun append(producer: String, build: (head: AuditChainLink?) -> AuditOutboxRecord)
}

/**
 * Opt-in durable [AuditEventPublisher] (ADR-0323 phase 1): hash-links every event into the
 * producer's chain and writes it to the transactional outbox, from where the service's existing
 * outbox dispatcher relays it to the audit topic.
 *
 * Selected only with the BUILD property `openbank.audit.publisher=hash-linked-outbox`; without it
 * this bean does not exist and [LoggingAuditEventPublisher] remains the default. A service that
 * sets the property must also provide an [AuditChainOutbox] bean, or the build fails with an
 * unsatisfied dependency — deliberately loud rather than a silent fallback to logging.
 */
@ApplicationScoped
@Alternative
@Priority(HashLinkedOutboxAuditEventPublisher.PRIORITY)
@IfBuildProperty(
    name = HashLinkedOutboxAuditEventPublisher.SELECTOR,
    stringValue = HashLinkedOutboxAuditEventPublisher.MODE,
)
class HashLinkedOutboxAuditEventPublisher(
    private val outbox: AuditChainOutbox,
    @ConfigProperty(name = "quarkus.application.name") private val producer: String,
) : AuditEventPublisher {
    private val aggregateId: UUID = producerAggregateId(producer)

    override suspend fun publish(event: AuditEvent) {
        // No JVM lock here: serialisation is the port's per-producer DB lock (see AuditChainOutbox).
        outbox.append(producer) { head ->
            val envelope = AuditChain.link(event, producer, head)
            AuditOutboxRecord(
                eventId = event.eventId,
                aggregateId = aggregateId,
                eventType = event.operation,
                payload = envelope.canonicalJson,
                link = envelope.link,
            )
        }
    }

    companion object {
        const val SELECTOR = "openbank.audit.publisher"
        const val MODE = "hash-linked-outbox"
        const val PRIORITY = 100

        fun producerAggregateId(producer: String): UUID =
            UUID.nameUUIDFromBytes("openbank-audit-chain:$producer".toByteArray(Charsets.UTF_8))
    }
}
