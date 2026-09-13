// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.persistence

import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "party_merge_index")
class PartyMergeIndexEntity : PanacheEntity() {
    @Column(name = "retired_party_id", nullable = false, unique = true)
    lateinit var retiredPartyId: UUID

    @Column(name = "survivor_party_id", nullable = false)
    lateinit var survivorPartyId: UUID

    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant
}

/**
 * ADR-0179 / issue #1984: the read-side half of `merged_into` adoption in openbank-audit-service.
 *
 * `audit_entries` cannot be rewritten (V2's append-only RULEs, see V15's migration comment), so a
 * merge is adopted at READ time instead: this repository records the `retired -> survivor` edge
 * from each `PARTY_MERGED` event, and [ancestorsOf] walks it BACKWARD from a survivor to recover
 * every id that was, transitively, merged into it. [AuditRepository.findByAggregateId] is the one
 * caller — both auditor-facing routes (`/entries/{aggregateId}`, the investigator query, and
 * `/customer/{partyId}`, the customer's own access log) go through it, so fixing that one
 * chokepoint fixes both, the same shape as customer-edge's `PartyMergeResolver` (#3901) at its
 * chokepoint.
 *
 * This table is NOT audit evidence. It carries no hash chain and no append-only RULE — it is an
 * ordinary, mutable lookup projection that exists purely to make a query answer completely. The
 * `PARTY_MERGED` event that grounds each row is itself stored as a normal, tamper-evident
 * `audit_entries` row exactly as it always was; nothing about that path changes.
 */
@ApplicationScoped
class PartyMergeIndexRepository : PanacheRepository<PartyMergeIndexEntity> {

    /**
     * Records that [retiredPartyId] was merged into [survivorPartyId].
     *
     * Idempotent via a plain check-then-insert, not a database-level upsert: `retired_party_id`
     * carries a UNIQUE constraint as a backstop, but the real invariant is upstream —
     * party-service's `mergeParty` rejects merging a party that is already `MERGED`
     * (`PartyService.kt`), so a given retired id can be the source of at most one merge ever, and
     * this consumer group has a single member (mirrors [AuditRepository]'s own chain-write note),
     * so there is no concurrent writer to race against. A redelivery of the same `PARTY_MERGED`
     * message therefore finds the row already there and does nothing.
     */
    suspend fun recordMerge(retiredPartyId: UUID, survivorPartyId: UUID, recordedAt: Instant) {
        val exists = Panache.withSession {
            find("retiredPartyId", retiredPartyId).firstResult()
        }.awaitSuspending() != null
        if (exists) return
        val e = PartyMergeIndexEntity().also {
            it.retiredPartyId = retiredPartyId
            it.survivorPartyId = survivorPartyId
            it.recordedAt = recordedAt
        }
        Panache.withTransaction { persist(e) }.awaitSuspending()
    }

    /**
     * [aggregateId] plus every id that was, transitively, merged into it — the full set of
     * aggregate ids whose `audit_entries` rows belong to the same party history as [aggregateId].
     *
     * Returns just `[aggregateId]` when it does not parse as a UUID, or when nothing was ever
     * recorded as merged into it — which is every non-party aggregate id (account, transaction,
     * …) and every party that was never on the receiving end of a merge. Calling this
     * unconditionally from [AuditRepository.findByAggregateId] is therefore a safe no-op for
     * every aggregate type this service was not written to reason about.
     *
     * Bounded to [MAX_HOPS] hops with a visited set, the same ceiling and the same reason as
     * customer-edge's `PartyMergeResolver` (#3901): real chains are 0-1 hops (A merged into B is
     * the overwhelming case), but a merge can chain over time — party-service's guard only
     * refuses merging INTO an already-merged target *at merge time*; it does not prevent that
     * target from itself being merged elsewhere later (A -> B, then afterwards B -> C) — and a
     * corrupted or cyclic edge in this table must not turn a read into an unbounded query.
     */
    suspend fun ancestorsOf(aggregateId: String): List<String> {
        val start = runCatching { UUID.fromString(aggregateId) }.getOrNull() ?: return listOf(aggregateId)
        val visited = linkedSetOf(start)
        var frontier = listOf(start)
        var hop = 0
        while (frontier.isNotEmpty() && hop < MAX_HOPS) {
            val next = Panache.withSession {
                find("survivorPartyId in ?1", frontier).list()
            }.awaitSuspending().map { it.retiredPartyId }.filter { visited.add(it) }
            frontier = next
            hop++
        }
        return visited.map { it.toString() }
    }

    private companion object {
        const val MAX_HOPS = 5
    }
}
