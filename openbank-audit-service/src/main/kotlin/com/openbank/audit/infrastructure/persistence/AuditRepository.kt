// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.persistence

import com.openbank.audit.domain.model.AttributionSource
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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

    /**
     * Provenance of [occurredAt] — `EVENT` or `INGEST` ([OccurredAtSource]), NULL on any row
     * written before V11 (#3883).
     *
     * NULL is "unknown", and it has to stay unknown: `audit_entries` carries a
     * `DO INSTEAD NOTHING` rule on UPDATE (V2), so a backfill would report success and change
     * nothing — and there is no data to backfill FROM, since the discarded producer key was never
     * stored in a column. The pre-V11 rows keep whatever `occurred_at` they were given; this
     * column only stops NEW rows from being ambiguous the same way. Same shape as `hash_version`
     * (V10): record the boundary rather than rewrite history.
     *
     * NOT part of [chainHash] — like the ADR-0226 channel columns, it is derived from the
     * producer's raw JSON, which the chain already covers via the `payload` hash. Adding it to the
     * canonical string would change the hash of every future row for no evidential gain and would
     * split the chain a second time.
     */
    @Column(name = "occurred_at_source", length = 8)
    var occurredAtSource: String? = null

    /**
     * Provenance of [sourceService] — `EVENT`, `TOPIC` or `ABSENT` ([AttributionSource]), NULL on
     * any row written before V13 (#3994).
     *
     * Same contract as [occurredAtSource] above and for the same reasons: NULL stays NULL (the
     * `DO INSTEAD NOTHING` rule from V2 makes a backfill a silent no-op, and there is nothing to
     * backfill from — the topic a pre-V13 row arrived on was never stored), and it is NOT part of
     * [chainHash], because it describes where the consumer got a value rather than asserting a new
     * evidential fact. `source_service` itself remains chain-hashed exactly as before.
     */
    @Column(name = "source_service_source", length = 8)
    var sourceServiceSource: String? = null

    // ── Tamper-evidence (hash chain, ADR-0023 spirit applied to the operational log) ──
    @Column(name = "prev_hash")
    var prevHash: String? = null

    @Column(name = "record_hash")
    var recordHash: String? = null

    /**
     * Which canonical form [AuditRepository.chainHash] used for [recordHash].
     *
     * NULL means the pre-#3586 form, which hashed nanosecond `Instant.toString()` while
     * `timestamptz` keeps microseconds — those rows are permanently unverifiable because the lost
     * digits are not in the database (#3505). They are counted on their own rather than reported
     * as broken: a failure to VERIFY is not evidence of tampering, and the pre-V5 `unchained`
     * bucket already means something else ("never had a hash at all").
     *
     * It is a version number and not a boolean so the next canonical change — if there ever is
     * one — is a new value rather than a second ad-hoc column, and so the boundary stays legible
     * in the data instead of living in a comment.
     */
    @Column(name = "hash_version")
    var hashVersion: Short? = null

    // ── Cross-channel correlation (ADR-0226): query indexes, NOT part of the chain hash — the
    // producer's raw event JSON (already hashed via `payload`) carries these fields verbatim, so
    // tamper-evidence covers them without recomputing every pre-V9 row.
    //
    // length=32 (V14, issue #4660): widened from 16. The bare JSON key "channel" turned out to be
    // independently populated by THREE producers — AuditChannel (ADR-0226, this column's original
    // intent), OnboardingChannel (party-service) and ComplaintChannel (dispute-service) — with no
    // shared vocabulary, so AuditConsumer now namespaces the value by source topic before storing
    // it (see resolveChannel there). "onboarding:MOBILE_APP" alone is 22 characters.
    @Column(name = "channel", length = 32)
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

    // ADR-0179 / issue #1984: follows `merged_into` at read time so a party-history query does
    // not stop at the retired id (see PartyMergeIndexRepository and findByAggregateId below).
    // Optional in tests the same way meterRegistry is elsewhere in this service — a unit test
    // that constructs this repo by hand and never sets it gets today's un-merge-aware behaviour,
    // not a crash.
    @Inject lateinit var mergeIndex: PartyMergeIndexRepository

    // Chain writes are serialised in-process: the consumer group has a single member
    // (group.id=audit-service, 1 replica), so a mutex is sufficient — and much simpler than
    // row-locking through the reactive session. If the service ever scales horizontally the
    // chain MUST move to a DB-level advisory lock first.
    private val chainMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun save(entry: AuditEntry) {
        chainMutex.withLock {
            // Kafka delivery is at least once.  The producer's immutable event id is copied to
            // entry_id, so a retry must be a no-op before it can advance the append-only chain.
            val alreadyRecorded = Panache.withSession {
                find("entryId", entry.id).firstResult()
            }.awaitSuspending()
            if (alreadyRecorded != null) return
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
                it.occurredAtSource = entry.occurredAtSource.name
                it.sourceServiceSource = entry.sourceServiceSource.name
                it.channel = entry.channel
                it.actChain = entry.actChain.takeIf { chain -> chain.isNotEmpty() }
                    ?.let { chain -> actChainJson.writeValueAsString(chain) }
                it.sessionId = entry.sessionId
                it.onBehalfOf = entry.onBehalfOf
                it.delegationId = entry.delegationId
                it.prevHash = prevHash
                it.recordHash = chainHash(prevHash, stored)
                it.hashVersion = HASH_VERSION_MICROS
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
     *
     * Rows in the legacy segment ([isLegacyHashVersion]) are counted and skipped, and the walk then
     * re-anchors `expectedPrev` on the first verifiable row's OWN prevHash. Without that re-anchor
     * the boundary link would be compared against a hash from the other canonical form, and the
     * first GOOD row would report as tampering — the same false positive this exists to remove,
     * moved one row along.
     */
    @Suppress("LoopWithTooManyJumpStatements", "NestedBlockDepth", "CyclomaticComplexMethod")
    suspend fun verifyChain(fromEntryId: UUID? = null): ChainVerification {
        var checked = 0L
        var unchained = 0L
        var unverifiableLegacy = 0L
        var expectedPrev = GENESIS_HASH
        var page = 0
        var found = fromEntryId == null // skip scan if no anchor requested
        var reAnchorAtNextVerifiable = false

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
                // A chained row whose hash_version is NULL was written with the pre-#3586
                // canonical form, which hashed nanosecond precision the database then truncated
                // (#3505). Its original digits are not in the database, so no rendering of the
                // stored row can reproduce its hash — it is unverifiable, permanently, and that
                // is NOT evidence of tampering. Counting it separately is the whole point: the
                // pre-V5 `unchained` bucket means "never had a hash", which is a different fact,
                // and merging the two would let a real gap hide inside a known one.
                if (isLegacyHashVersion(e)) {
                    unverifiableLegacy++
                    reAnchorAtNextVerifiable = true
                    continue
                }
                if (reAnchorAtNextVerifiable) {
                    expectedPrev = e.prevHash ?: GENESIS_HASH
                    reAnchorAtNextVerifiable = false
                }
                val recomputed = chainHash(e.prevHash ?: GENESIS_HASH, e.toDomain())
                if (chainLinkBroken(e.prevHash, expectedPrev, e.recordHash!!, recomputed)) {
                    return ChainVerification(false, checked, unchained, unverifiableLegacy, e.entryId)
                }
                expectedPrev = e.recordHash!!
                checked++
            }
            page++
        }
        if (anchorMissed(found, fromEntryId)) {
            return ChainVerification(false, 0, 0, 0, anchorNotFound = true)
        }
        return ChainVerification(true, checked, unchained, unverifiableLegacy)
    }

    /**
     * Appends a row with the chain fields supplied verbatim, bypassing [save].
     *
     * Test seam, and it has to look like this for two reasons that are properties of the schema
     * rather than choices:
     *
     * 1. `audit_entries` is append-only at the DATABASE level — `V2__compliance_fields.sql`
     *    installs `RULE no_update_audit ... DO INSTEAD NOTHING`, which *silently discards* an
     *    UPDATE rather than refusing it. A legacy row can therefore only be appended, never
     *    demoted. (That same rule is why the legacy hashes could not have been backfilled even if
     *    rewriting tamper-evidence had been acceptable.)
     * 2. The id comes from `nextval('audit_entries_seq')` — the SAME allocator Hibernate uses — so
     *    ids stay monotonic. Mixing allocators broke two earlier attempts: `MAX(id)+1` collides
     *    with an id Hibernate has already reserved from its cached block (`V4` gives the sequence
     *    `INCREMENT BY 50`), and letting Hibernate write a neighbouring row lands it at a LOWER id
     *    than a raw `nextval`, scrambling chain order. A test needing consecutive rows must write
     *    BOTH of them through this seam.
     *
     * Returns rows inserted so the caller can assert the seam did something — a seam that no-ops
     * makes the test it supports pass for the wrong reason, which is how the first version of this
     * test went green against unchanged code.
     */
    @Suppress("LongParameterList")
    suspend fun appendRawRow(entry: AuditEntry, prevHash: String, recordHash: String, hashVersion: Short?): Int =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(
                    """
                    INSERT INTO audit_entries
                      (id, entry_id, event_type, aggregate_type, aggregate_id, actor_id, actor_type,
                       payload, source_service, correlation_id, occurred_at, recorded_at,
                       prev_hash, record_hash, hash_version)
                    VALUES
                      (nextval('audit_entries_seq'), :entryId, :eventType, :aggregateType,
                       :aggregateId, :actorId, :actorType, :payload, :sourceService, :correlationId,
                       :occurredAt, :recordedAt, :prevHash, :recordHash, :hashVersion)
                    """.trimIndent(),
                )
                    .setParameter("entryId", entry.id)
                    .setParameter("eventType", entry.eventType)
                    .setParameter("aggregateType", entry.aggregateType)
                    .setParameter("aggregateId", entry.aggregateId)
                    .setParameter("actorId", entry.actorId)
                    .setParameter("actorType", entry.actorType)
                    .setParameter("payload", entry.payload)
                    .setParameter("sourceService", entry.sourceService)
                    .setParameter("correlationId", entry.correlationId)
                    .setParameter("occurredAt", entry.occurredAt.truncatedTo(ChronoUnit.MICROS))
                    .setParameter("recordedAt", entry.recordedAt.truncatedTo(ChronoUnit.MICROS))
                    .setParameter("prevHash", prevHash)
                    .setParameter("recordHash", recordHash)
                    .setParameter("hashVersion", hashVersion)
                    .executeUpdate()
            }
        }.awaitSuspending()

    /**
     * The audit trail for [aggregateId] — and, for a party, its full history: ADR-0179 /
     * issue #1984. A party merge retires the duplicate (`PartyStatus.MERGED` + `merged_into`) and
     * moves nothing else, so the rows recorded under the retired id before the merge would
     * otherwise be invisible to a query for the survivor — exactly the gap #3901 closed for
     * customer-edge's JWT resolution, on the read side here instead of the write side, because
     * `audit_entries` cannot be rewritten (see V15's migration comment).
     *
     * [PartyMergeIndexRepository.ancestorsOf] is a safe no-op for every [aggregateId] this is not
     * true of: an id that never had anything merged into it (the common case, and every non-party
     * aggregate) resolves to just itself, so this is unconditionally correct to call for both
     * callers of this method — the auditor investigator route AND the customer's own access log.
     */
    suspend fun findByAggregateId(aggregateId: String, limit: Int = 100): List<AuditEntry> {
        val ids = if (::mergeIndex.isInitialized) mergeIndex.ancestorsOf(aggregateId) else listOf(aggregateId)
        return Panache.withSession {
            if (ids.size == 1) {
                find("aggregateId = ?1 ORDER BY occurredAt DESC", aggregateId).page(0, limit).list()
            } else {
                find("aggregateId in ?1 ORDER BY occurredAt DESC", ids).page(0, limit).list()
            }
        }.awaitSuspending().map { it.toDomain() }
    }

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
        // Panache's Parameters.with/.and are deprecated (flagged by CodeQL); the overload taking a
        // plain Map is the supported one and reads the same here.
        val params = mutableMapOf<String, Any>("grantor" to grantorPartyId)
        delegatePartyId?.let {
            clauses += "actorId = :delegate"
            params["delegate"] = it
        }
        delegationId?.let {
            clauses += "delegationId = :grant"
            params["grant"] = it
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
        // NULL (pre-V11) reads back as INGEST: those rows may hold ingest time and cannot prove
        // otherwise, so the weaker claim is the honest one.
        occurredAtSource = occurredAtSource?.let { OccurredAtSource.valueOf(it) } ?: OccurredAtSource.INGEST,
        // NULL (pre-V13) reads back as ABSENT for the same reason: those rows cannot say who
        // supplied `source_service`, and for 76% of them the answer is that nobody did.
        sourceServiceSource = sourceServiceSource?.let { AttributionSource.valueOf(it) } ?: AttributionSource.ABSENT,
        channel = channel,
        actChain = actChain?.let { actChainJson.readValue(it, stringListType) } ?: emptyList(),
        sessionId = sessionId,
        onBehalfOf = onBehalfOf,
        delegationId = delegationId,
    )

    companion object {
        private const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val CHAIN_PAGE_SIZE = 500

        /**
         * Canonical form 2: every timestamp truncated to microseconds before hashing, so the value
         * hashed is the value `timestamptz` can give back. Form 1 is implicit (NULL) and is never
         * written again — it exists only as the marker on rows that predate #3586.
         */
        internal const val HASH_VERSION_MICROS: Short = 2

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

/**
 * A chained row written with the pre-#3586 canonical form: its hash covered nanoseconds the
 * database truncated (#3505), so it can never be recomputed — unverifiable, not tampered with.
 *
 * File-scope rather than a method: it is a predicate about a ROW, and keeping it off the repository
 * also keeps that class under detekt's function-count threshold.
 */
private fun chainLinkBroken(prevHash: String?, expectedPrev: String, recordHash: String, recomputed: String) =
    prevHash != expectedPrev || recordHash != recomputed

private fun anchorMissed(found: Boolean, fromEntryId: UUID?) = !found && fromEntryId != null

private fun isLegacyHashVersion(e: AuditEntryEntity) = e.recordHash != null && e.hashVersion == null

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
    /**
     * Rows that DO carry a `record_hash` but were written with the pre-#3586 canonical form, which
     * hashed nanoseconds the database truncated (#3505). Permanently unverifiable — the lost digits
     * are not in the database — and deliberately not folded into [unchained], which means the
     * different fact "never had a hash at all". Keeping them apart is what stops a real gap hiding
     * inside a known one.
     */
    val unverifiableLegacy: Long = 0,
    val firstBrokenEntryId: UUID? = null,
    /** True when the requested fromEntryId anchor was not found in the chain; intact is false. */
    val anchorNotFound: Boolean = false,
)
