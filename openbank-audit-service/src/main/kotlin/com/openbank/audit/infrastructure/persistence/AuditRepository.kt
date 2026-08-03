// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.persistence

import com.openbank.audit.domain.model.AuditEntry
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(name = "audit_entries")
class AuditEntryEntity : PanacheEntity() {
    @Column(name = "entry_id", nullable = false, unique = true)
    lateinit var entryId: UUID

    @Column(name = "event_type", nullable = false)
    lateinit var eventType: String

    @Column(name = "aggregate_type", nullable = false)
    lateinit var aggregateType: String

    @Column(name = "aggregate_id", nullable = false)
    lateinit var aggregateId: String

    @Column(name = "actor_id")
    var actorId: String? = null

    @Column(name = "actor_type")
    var actorType: String? = null

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    lateinit var payload: String

    @Column(name = "source_service", nullable = false)
    lateinit var sourceService: String

    @Column(name = "correlation_id")
    var correlationId: String? = null

    @Column(name = "occurred_at", nullable = false)
    lateinit var occurredAt: Instant

    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant

    // ── Tamper-evidence (hash chain, ADR-0023 spirit applied to the operational log) ──
    @Column(name = "prev_hash")
    var prevHash: String? = null

    @Column(name = "record_hash")
    var recordHash: String? = null

    // ── Cross-channel correlation (ADR-0226): query indexes, NOT part of the chain hash — the
    // producer's raw event JSON (already hashed via `payload`) carries these fields verbatim, so
    // tamper-evidence covers them without recomputing every pre-V9 row.
    @Column(name = "channel", length = 16)
    var channel: String? = null

    /** JSON array string (e.g. `["agent-session:7f3…","mcp-cli"]`); null when the action was direct. */
    @Column(name = "act_chain", columnDefinition = "TEXT")
    var actChain: String? = null

    @Column(name = "session_id", length = 100)
    var sessionId: String? = null

    // ── Delegated-action correlation (ADR-0232 D5): same rule as the V9 columns above — the
    // producer's raw event JSON carries both verbatim and IS chain-hashed via `payload`, so these
    // are a lookup path for the grantor transparency query, never evidence in their own right.
    @Column(name = "on_behalf_of", length = 64)
    var onBehalfOf: String? = null

    @Column(name = "delegation_id", length = 64)
    var delegationId: String? = null
}

@ApplicationScoped
class AuditRepository : PanacheRepository<AuditEntryEntity> {

    // Chain writes are serialised in-process: the consumer group has a single member
    // (group.id=audit-service, 1 replica), so a mutex is sufficient — and much simpler than
    // row-locking through the reactive session. If the service ever scales horizontally the
    // chain MUST move to a DB-level advisory lock first.
    private val chainMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun save(entry: AuditEntry) {
        chainMutex.withLock {
            val prev = Panache.withSession {
                find("ORDER BY id DESC").page(0, 1).firstResult()
            }.awaitSuspending()
            val prevHash = prev?.recordHash ?: GENESIS_HASH
            // Persist exactly the value that gets hashed (#3505). `occurred_at`/`recorded_at` are
            // TIMESTAMPTZ, which keeps MICROseconds, while a java.time.Instant carries nanoseconds
            // — on Linux, where the pods run, Instant.now() really does produce them. Hashing the
            // unrounded value and storing the rounded one makes every link permanently
            // unverifiable: verifyChain rebuilds the entry from the row, so the lost digits can
            // never come back. Normalising here (and again inside chainHash) makes the two sides
            // agree by construction rather than by luck of the platform clock.
            val stored = entry.normalisedForStorage()
            val e = AuditEntryEntity().also {
                it.entryId = entry.id
                it.eventType = entry.eventType
                it.aggregateType = entry.aggregateType
                it.aggregateId = entry.aggregateId
                it.actorId = entry.actorId
                it.actorType = entry.actorType
                it.payload = entry.payload
                it.sourceService = entry.sourceService
                it.correlationId = entry.correlationId
                it.occurredAt = stored.occurredAt
                it.recordedAt = stored.recordedAt
                it.channel = entry.channel
                it.actChain = entry.actChain.takeIf { chain -> chain.isNotEmpty() }
                    ?.let { chain -> actChainJson.writeValueAsString(chain) }
                it.sessionId = entry.sessionId
                it.onBehalfOf = entry.onBehalfOf
                it.delegationId = entry.delegationId
                it.prevHash = prevHash
                it.recordHash = chainHash(prevHash, stored)
            }
            Panache.withTransaction { persist(e) }.awaitSuspending()
        }
    }

    /**
     * Walk the whole chain oldest-first and recompute every link. Any in-place edit, delete
     * or re-order breaks the recomputation at the first affected row. Pre-chain rows (no
     * record_hash, written before V5) are reported but cannot be verified retroactively.
     *
     * @param fromEntryId  When provided, start the walk at the row whose `entry_id` equals
     *   this value (inclusive). This allows a DORA incident responder to re-verify only the
     *   tail of the chain from a known-good anchor, rather than walking the whole log.
     *   The anchor row's own prevHash is used as the expected-previous for the walk, so
     *   the check is self-contained from that point onward.
     *   If the id is not found the walk starts from the beginning (fail-safe).
     */
    @Suppress("LoopWithTooManyJumpStatements", "NestedBlockDepth")
    suspend fun verifyChain(fromEntryId: UUID? = null): ChainVerification {
        var checked = 0L
        var unchained = 0L
        var expectedPrev = GENESIS_HASH
        var page = 0
        var found = fromEntryId == null // skip scan if no anchor requested

        while (true) {
            val batch = Panache.withSession {
                find("ORDER BY id ASC").page(page, CHAIN_PAGE_SIZE).list()
            }.awaitSuspending()
            if (batch.isEmpty()) break
            for (e in batch) {
                // Anchor scan: skip rows until we reach the requested fromEntryId.
                if (!found) {
                    if (e.entryId == fromEntryId) {
                        // Start from this row's own prevHash so the walk is self-consistent.
                        expectedPrev = e.prevHash ?: GENESIS_HASH
                        found = true
                    } else {
                        continue
                    }
                }
                if (e.recordHash == null) {
                    unchained++
                    continue
                }
                val recomputed = chainHash(e.prevHash ?: GENESIS_HASH, e.toDomain())
                if (chainLinkBroken(e.prevHash, expectedPrev, e.recordHash!!, recomputed)) {
                    return ChainVerification(
                        intact = false,
                        checked = checked,
                        unchained = unchained,
                        firstBrokenEntryId = e.entryId,
                    )
                }
                expectedPrev = e.recordHash!!
                checked++
            }
            page++
        }
        if (anchorMissed(found, fromEntryId)) {
            return ChainVerification(intact = false, checked = 0, unchained = 0, anchorNotFound = true)
        }
        return ChainVerification(
            intact = true,
            checked = checked,
            unchained = unchained,
            firstBrokenEntryId = null,
        )
    }

    suspend fun findByAggregateId(aggregateId: String, limit: Int = 100): List<AuditEntry> = Panache.withSession {
        find("aggregateId = ?1 ORDER BY occurredAt DESC", aggregateId).page(0, limit).list()
    }.awaitSuspending().map { it.toDomain() }

    /**
     * Person-across-channels query (ADR-0226 D3): every entry an actor produced, optionally
     * narrowed to one ingress channel. The V1 partial actor_id index carries the lookup.
     */
    suspend fun findByActorId(actorId: String, channel: String? = null, limit: Int = 100): List<AuditEntry> =
        Panache.withSession {
            if (channel == null) {
                find("actorId = ?1 ORDER BY occurredAt DESC", actorId).page(0, limit).list()
            } else {
                find("actorId = ?1 AND channel = ?2 ORDER BY occurredAt DESC", actorId, channel).page(0, limit).list()
            }
        }.awaitSuspending().map { it.toDomain() }

    /**
     * The grantor transparency query (ADR-0232 D5, #2990 AC10): every action taken ON BEHALF OF
     * [grantorPartyId] by a delegate, newest first, optionally narrowed to one [delegatePartyId]
     * (the actor) or one [delegationId] (the grant).
     *
     * Deliberately NOT the same question as [findByActorId] or [findByAggregateId]. The grantor is
     * neither the actor nor the aggregate on a delegated action — the actor is the delegate and
     * the aggregate is the delegate's own party — so before this column existed the grantor's own
     * `/audit/customer/{partyId}` view showed nothing at all about what was done with their money.
     *
     * The narrowing parameters are ANDed and both are optional, which is why the query is
     * assembled rather than written as one string: a null must mean "unfiltered", and a
     * `(?3 IS NULL OR delegationId = ?3)` form silently defeats the partial index.
     */
    suspend fun findOnBehalfOf(
        grantorPartyId: String,
        delegatePartyId: String? = null,
        delegationId: String? = null,
        limit: Int = 100,
    ): List<AuditEntry> {
        val clauses = mutableListOf("onBehalfOf = :grantor")
        val params = io.quarkus.panache.common.Parameters.with("grantor", grantorPartyId)
        delegatePartyId?.let {
            clauses += "actorId = :delegate"
            params.and("delegate", it)
        }
        delegationId?.let {
            clauses += "delegationId = :grant"
            params.and("grant", it)
        }
        val query = clauses.joinToString(" AND ") + " ORDER BY occurredAt DESC"
        return Panache.withSession {
            find(query, params).page(0, limit).list()
        }.awaitSuspending().map { it.toDomain() }
    }

    /** Current chain head (most-recently-inserted row) plus the total row count, for anchoring. */
    suspend fun chainHead(): ChainHead? {
        val head = Panache.withSession {
            find("ORDER BY id DESC").page(0, 1).firstResult()
        }.awaitSuspending() ?: return null
        val total = Panache.withSession { count() }.awaitSuspending()
        return ChainHead(head.entryId, head.recordHash, total)
    }

    /** The live `record_hash` of a single entry, used to confirm a signed anchor's attested head. */
    suspend fun recordHashOf(entryId: UUID): String? = Panache.withSession {
        find("entryId = ?1", entryId).firstResult()
    }.awaitSuspending()?.recordHash

    private fun AuditEntryEntity.toDomain() = AuditEntry(
        entryId, eventType, aggregateType, aggregateId, actorId, actorType,
        payload, sourceService, correlationId, occurredAt, recordedAt,
        channel = channel,
        actChain = actChain?.let { actChainJson.readValue(it, stringListType) } ?: emptyList(),
        sessionId = sessionId,
        onBehalfOf = onBehalfOf,
        delegationId = delegationId,
    )

    private fun chainLinkBroken(prevHash: String?, expectedPrev: String, recordHash: String, recomputed: String) =
        prevHash != expectedPrev || recordHash != recomputed

    private fun anchorMissed(found: Boolean, fromEntryId: UUID?) = !found && fromEntryId != null

    companion object {
        private const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val CHAIN_PAGE_SIZE = 500

        private val actChainJson = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        private val stringListType = object : com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}

        /**
         * SHA-256 over the previous link + every evidential field. The payload is hashed
         * separately first so a multi-megabyte payload contributes a fixed-size block and
         * field boundaries stay unambiguous (the '|' separator cannot be confused by
         * payload content).
         */
        internal fun chainHash(prevHash: String, entry: AuditEntry): String {
            val payloadHash = sha256(entry.payload)
            val e = entry.normalisedForStorage()
            val canonical = listOf(
                prevHash, e.id.toString(), e.eventType, e.aggregateType,
                e.aggregateId, e.actorId ?: "", e.actorType ?: "", payloadHash,
                e.sourceService, e.correlationId ?: "",
                e.occurredAt.toString(), e.recordedAt.toString(),
            ).joinToString("|")
            return sha256(canonical)
        }

        /**
         * The entry as the database will hold it. `occurred_at`/`recorded_at` are TIMESTAMPTZ —
         * microsecond precision — so any nanosecond digits are dropped on persist. Hashing the
         * pre-truncation value is what made every link unverifiable (#3505); truncating here means
         * the write side and the read side hash the same characters whatever precision the source
         * clock had.
         */
        internal fun AuditEntry.normalisedForStorage(): AuditEntry = copy(
            occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS),
            recordedAt = recordedAt.truncatedTo(ChronoUnit.MICROS),
        )

        private fun sha256(input: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Chain head snapshot used to capture a signed anchor (see [AuditRepository.chainHead]). */
data class ChainHead(
    val entryId: UUID,
    /** Head row's `record_hash`; null only for a head written before the V5 chain migration. */
    val recordHash: String?,
    val count: Long,
)

/** Result of a full hash-chain walk (see [AuditRepository.verifyChain]). */
data class ChainVerification(
    val intact: Boolean,
    val checked: Long,
    /** Rows written before the chain existed (V5) — counted, not verifiable. */
    val unchained: Long,
    val firstBrokenEntryId: UUID? = null,
    /** True when the requested fromEntryId anchor was not found in the chain; intact is false. */
    val anchorNotFound: Boolean = false,
)
