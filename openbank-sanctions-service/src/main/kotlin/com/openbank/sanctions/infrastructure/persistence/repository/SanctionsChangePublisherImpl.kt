// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sanctions.application.port.out.SanctionsChangePublisher
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import com.openbank.sanctions.application.port.out.SanctionsPublicationOutcome
import com.openbank.sanctions.domain.model.SanctionsListType
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.logging.Log
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Journal selection, outbox writes and exact-selection deletion share ONE Hibernate transaction. */
@ApplicationScoped
class SanctionsChangePublisherImpl(
    private val outbox: SanctionsOutboxRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.sanctions.list-change.storm-threshold-share", defaultValue = "0.5")
    private val stormThreshold: Double,
) : SanctionsChangePublisher {
    override suspend fun publishPending(listId: UUID, listType: SanctionsListType): SanctionsPublicationOutcome =
        Panache.withTransaction {
            Panache.getSession().chain { session ->
                uni(CoroutineScope(Dispatchers.Unconfined)) {
                    require(stormThreshold.isFinite() && stormThreshold > 0 && stormThreshold <= 1)
                    // Population and selected journal rows must describe the same committed snapshot.
                    session.createNativeQuery<Any>("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                        .executeUpdate().awaitSuspending()
                    val previousStorm = session.createNativeQuery(
                        "SELECT COALESCE(last_storm_fingerprint, '') FROM sanctions_change_publication " +
                            "WHERE list_type = :type FOR UPDATE",
                        String::class.java,
                    ).setParameter("type", listType.name).singleResult.awaitSuspending()
                    // A source key that was too invalid to publish can be repaired later. Archive
                    // its historical journal rows only when a bounded current key exists; this
                    // update shares the outbox transaction, so publication failure rolls it back.
                    session.createNativeQuery<Any>(SanctionsPublicationSql.RESOLVE_REPAIRED_IDENTITIES)
                        .setParameter("type", listType.name).setParameter("maxBytes", MAX_ID_BYTES)
                        .executeUpdate().awaitSuspending()
                    session.createNativeQuery<Any>(SanctionsPublicationSql.CREATE_SELECTION)
                        .executeUpdate().awaitSuspending()
                    val selectedCount = session.createNativeQuery<Any>(SanctionsPublicationSql.SELECT_PENDING)
                        .setParameter("type", listType.name).executeUpdate().awaitSuspending()
                    if (selectedCount == 0) {
                        SanctionsPublicationOutcome.NO_CHANGES
                    } else {
                        publishSelection(session, listId, listType, previousStorm)
                    }
                }
            }
        }.awaitSuspending()

    private suspend fun publishSelection(
        session: Mutiny.Session,
        listId: UUID,
        listType: SanctionsListType,
        previousStorm: String,
    ): SanctionsPublicationOutcome {
        val summary = summarize(session, listType)
        if (summary.reason != null) {
            val reason = summary.reason
            val fingerprint = summary.fingerprint
            if (fingerprint != previousStorm) {
                persist(
                    listId,
                    EVENT_STORM,
                    mapOf(
                        "listType" to listType.name,
                        "changeCount" to summary.changeCount,
                        "baselineEntryCount" to summary.baseline,
                        "reason" to reason,
                        "stormThresholdShare" to stormThreshold,
                        "evidenceFingerprint" to fingerprint,
                    ),
                )
                session.createNativeQuery<Any>(
                    "UPDATE sanctions_change_publication SET last_storm_fingerprint = :fingerprint " +
                        "WHERE list_type = :type",
                ).setParameter("fingerprint", fingerprint).setParameter("type", listType.name)
                    .executeUpdate().awaitSuspending()
                Log.errorf("Sanctions publication withheld for %s: %s; journal retained", listType, reason)
            }
            return SanctionsPublicationOutcome.WITHHELD
        }
        persistChunks(session, listId, listType, summary.changeCount)
        // Never delete by list or high-water mark: later commits can carry earlier sequence IDs.
        session.createNativeQuery<Any>(SanctionsPublicationSql.DELETE_SELECTION)
            .executeUpdate().awaitSuspending()
        session.createNativeQuery<Any>(
            "UPDATE sanctions_change_publication SET last_storm_fingerprint = NULL WHERE list_type = :type",
        ).setParameter("type", listType.name).executeUpdate().awaitSuspending()
        return SanctionsPublicationOutcome.PUBLISHED
    }

    private suspend fun summarize(session: Mutiny.Session, listType: SanctionsListType): Summary {
        for (sql in listOf(
            SanctionsPublicationSql.CREATE_CHANGES,
            SanctionsPublicationSql.CREATE_TARGETS,
            "ALTER TABLE sanctions_publication_targets ADD PRIMARY KEY (id)",
        )) {
            session.createNativeQuery<Any>(sql).executeUpdate().awaitSuspending()
        }
        val json = session.createNativeQuery(SanctionsPublicationSql.SUMMARY, String::class.java)
            .setParameter("type", listType.name).setParameter("maxBytes", MAX_ID_BYTES)
            .singleResult.awaitSuspending()
        val node = mapper.readTree(json)
        val count = node["changeCount"].asLong()
        val baseline = node["baseline"].asLong()
        val reason = when {
            node["missingIdentity"].asBoolean() -> "MISSING_SOURCE_ID"
            node["oversizedIdentity"].asBoolean() -> "SOURCE_ID_TOO_LARGE"
            baseline > 0 && count.toDouble() / baseline > stormThreshold -> "CHANGE_SHARE"
            else -> null
        }
        // Pending evidence is append-only. A newly visible commit changes count or maximum ID;
        // a successful drain clears the remembered signal. This is retry deduplication, not an attestation.
        val basis = "$reason:${node["selectedCount"].asLong()}:${node["maximumId"].asLong()}:$baseline"
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return Summary(count, baseline, reason, fingerprint)
    }

    private suspend fun persistChunks(session: Mutiny.Session, listId: UUID, listType: SanctionsListType, count: Long) {
        val publicationId = Ids.randomId()
        val chunkCount = (count + IDS_PER_EVENT - 1) / IDS_PER_EVENT
        var after = 0L
        var index = 0L
        while (index < chunkCount) {
            val chunk = session.createNativeQuery(SanctionsPublicationSql.TARGET_PAGE, String::class.java)
                .setParameter("after", after).setParameter("pageSize", IDS_PER_EVENT)
                .resultList.awaitSuspending().map { decode(it) }
            check(chunk.isNotEmpty()) { "Selected sanctions publication page is missing" }
            persist(
                listId,
                EVENT_CHANGED,
                mapOf(
                    "listType" to listType.name,
                    "changedExternalIds" to chunk.filter { it.active }.map { it.externalId },
                    "deactivatedExternalIds" to chunk.filterNot { it.active }.map { it.externalId },
                    "changeCount" to chunk.size,
                    "publicationId" to publicationId.toString(),
                    "chunkIndex" to index,
                    "chunkCount" to chunkCount,
                ),
            )
            // Flushing does not commit. Release Hibernate's managed rows between bounded pages;
            // a later failure still rolls back every previously flushed outbox row and the selection.
            session.flush().awaitSuspending()
            session.clear()
            after = chunk.last().id
            index += 1
        }
    }

    private suspend fun persist(listId: UUID, eventType: String, payload: Map<String, Any>) {
        outbox.persistInTransaction(
            OutboxMessage(
                aggregateId = listId,
                eventType = eventType,
                payload = mapper.writeValueAsString(
                    payload + mapOf(
                        "aggregateId" to listId.toString(),
                        "aggregateType" to "SanctionsList",
                        "occurredAt" to Instant.now(clock).toString(),
                    ),
                ),
                createdAt = Instant.now(clock),
            ),
        ).awaitSuspending()
    }

    private fun decode(json: String): Target {
        val node = mapper.readTree(json)
        check(!node["external_id"].isNull) { "Missing source identity passed publication summary" }
        return Target(node["id"].asLong(), node["external_id"].asText(), node["active"].asBoolean())
    }

    private data class Target(val id: Long, val externalId: String, val active: Boolean)
    private data class Summary(val changeCount: Long, val baseline: Long, val reason: String?, val fingerprint: String)

    private companion object {
        const val EVENT_CHANGED = "SANCTIONS_LIST_CHANGED"
        const val EVENT_STORM = "SANCTIONS_LIST_CHANGE_STORM"

        // JSON-encoded IDs are bounded before chunking: at most 256 KiB plus envelope per event.
        const val MAX_ID_BYTES = 4096
        const val IDS_PER_EVENT = 64
    }
}
