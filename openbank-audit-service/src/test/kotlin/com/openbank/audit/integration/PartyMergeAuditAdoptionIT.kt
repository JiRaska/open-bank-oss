// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.application.AuditConsumer
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.persistence.PartyMergeIndexRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * ADR-0179 / issue #1984 ("consumer adoption of `merged_into`"), the audit-service slice.
 *
 * `audit_entries` is append-only at the database level (V2's `no_update_audit`/`no_delete_audit`
 * RULEs), so unlike #3901's write-time fix at customer-edge's identity chokepoint, this cannot
 * rewrite the `aggregate_id` of rows recorded before a merge — those rows correctly stay exactly
 * as they were. The fix is at READ time instead: [PartyMergeIndexRepository] records each merge's
 * `retired -> survivor` edge, and [AuditRepository.findByAggregateId] — the one method behind
 * both `GET /api/v1/audit/entries/{aggregateId}` (the auditor investigator query) and
 * `GET /api/v1/audit/customer/{partyId}` (the customer's own access log) — follows it backward so
 * a query for the survivor returns the WHOLE history, not just what was recorded after the merge.
 *
 * Falsifiability, in the same test as the fix rather than by reverting code: [AuditEntryEntity]'s
 * own literal-match finder (`aggregateId = ?1`, no merge awareness — precisely
 * `findByAggregateId`'s shape before this PR) is run alongside [AuditRepository.findByAggregateId]
 * against the identical rows. The literal query is incomplete; the fixed one is not. That is the
 * defect and the fix, both demonstrated, not merely asserted.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PartyMergeAuditAdoptionIT {

    @Inject
    lateinit var repository: AuditRepository

    @Inject
    lateinit var mergeIndex: PartyMergeIndexRepository

    private val consumer = AuditConsumer()

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun consumerFor(): AuditConsumer = consumer.also {
        it.repo = repository
        it.mergeIndex = mergeIndex
        it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
        it.clock = Clock.systemUTC()
    }

    /** Matches `PartyEvents.lifecycle`'s exact flat envelope for a pre-merge lifecycle event. */
    private fun partyLifecycleEvent(eventType: String, partyId: UUID, at: String) = """
        {
          "eventType": "$eventType",
          "partyId": "$partyId",
          "partyType": "INDIVIDUAL",
          "status": "ACTIVE",
          "kycStatus": "VERIFIED",
          "occurredAt": "$at"
        }
    """.trimIndent()

    /** Matches `PartyEvents.merged`'s exact flat envelope — the field names this fix depends on. */
    private fun partyMergedEvent(retired: UUID, survivor: UUID, at: String) = """
        {
          "eventType": "PARTY_MERGED",
          "partyId": "$retired",
          "mergedIntoPartyId": "$survivor",
          "status": "MERGED",
          "occurredAt": "$at",
          "actorId": "operator-1",
          "actorType": "HUMAN"
        }
    """.trimIndent()

    /** The literal, merge-UNAWARE query — exactly [AuditRepository.findByAggregateId]'s shape before this fix. */
    private fun literalAggregateIdMatch(aggregateId: String) = onEventLoop {
        Panache.withSession {
            repository.find("aggregateId = ?1 ORDER BY occurredAt DESC", aggregateId).list()
        }.awaitSuspending()
    }

    // ── the headline: a survivor's history includes what happened to the party before it merged ──

    @Test
    fun `a survivor's history includes rows recorded under the retired party before the merge`() {
        val retired = UUID.randomUUID()
        val survivor = UUID.randomUUID()

        onEventLoop {
            val c = consumerFor()
            // Before the merge: two lifecycle events under the RETIRED id.
            c.consume(partyLifecycleEvent("PARTY_CREATED", retired, "2026-01-01T09:00:00Z"))
            c.consume(partyLifecycleEvent("KYC_STATUS_CHANGED", retired, "2026-01-02T09:00:00Z"))
            // The merge itself.
            c.consume(partyMergedEvent(retired, survivor, "2026-01-03T09:00:00Z"))
            // After the merge: one event under the SURVIVOR id.
            c.consume(partyLifecycleEvent("KYC_STATUS_CHANGED", survivor, "2026-01-04T09:00:00Z"))
        }

        // FALSIFICATION: the literal, merge-unaware match sees only what was ever recorded under
        // the survivor's own id — the pre-merge history is invisible to it.
        val literal = literalAggregateIdMatch(survivor.toString())
        assertThat(literal).hasSize(1)
        assertThat(literal.single().eventType).isEqualTo("KYC_STATUS_CHANGED")

        // THE FIX: findByAggregateId follows merged_into backward and returns all four rows —
        // the two pre-merge, the merge event itself, and the one post-merge — newest first.
        val full = onEventLoop { repository.findByAggregateId(survivor.toString()) }
        assertThat(full).hasSize(4)
        assertThat(full.map { it.eventType }).containsExactly(
            "KYC_STATUS_CHANGED", // 01-04, post-merge, under survivor
            "PARTY_MERGED", // 01-03
            "KYC_STATUS_CHANGED", // 01-02, pre-merge, under retired
            "PARTY_CREATED", // 01-01, pre-merge, under retired
        )
        assertThat(full.map { it.occurredAt }).isSortedAccordingTo(Comparator.reverseOrder())
    }

    /**
     * The other direction must stay untouched: querying the RETIRED id (which nothing does in
     * production once #3901's edge resolver is in the loop, but the auditor route accepts any id)
     * returns only what was recorded under it — never the survivor's post-merge activity. The
     * pointer resolves backward (survivor -> its retired predecessors), never forward.
     */
    @Test
    fun `querying the retired id itself is unaffected — only the survivor's query gains history`() {
        val retired = UUID.randomUUID()
        val survivor = UUID.randomUUID()

        onEventLoop {
            val c = consumerFor()
            c.consume(partyLifecycleEvent("PARTY_CREATED", retired, "2026-01-01T09:00:00Z"))
            c.consume(partyMergedEvent(retired, survivor, "2026-01-02T09:00:00Z"))
            c.consume(partyLifecycleEvent("KYC_STATUS_CHANGED", survivor, "2026-01-03T09:00:00Z"))
        }

        val retiredHistory = onEventLoop { repository.findByAggregateId(retired.toString()) }
        assertThat(retiredHistory.map { it.eventType }).containsExactly("PARTY_MERGED", "PARTY_CREATED")
    }

    /**
     * Merges chain over time: party-service's guard only refuses merging INTO an
     * already-merged target, at merge time — it does not stop that target from later itself being
     * merged elsewhere (A -> B, then afterwards B -> C). A history query for the FINAL survivor
     * must walk the whole chain, not just one hop.
     */
    @Test
    fun `a chain of merges is followed to its end, recovering the whole history`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()

        onEventLoop {
            val consumer = consumerFor()
            consumer.consume(partyLifecycleEvent("PARTY_CREATED", a, "2026-01-01T09:00:00Z"))
            consumer.consume(partyMergedEvent(a, b, "2026-01-02T09:00:00Z"))
            consumer.consume(partyLifecycleEvent("PARTY_CREATED", b, "2026-01-02T10:00:00Z"))
            consumer.consume(partyMergedEvent(b, c, "2026-01-03T09:00:00Z"))
            consumer.consume(partyLifecycleEvent("KYC_STATUS_CHANGED", c, "2026-01-04T09:00:00Z"))
        }

        // FALSIFICATION: the literal match at the final survivor still sees only its own row.
        assertThat(literalAggregateIdMatch(c.toString())).hasSize(1)

        val full = onEventLoop { repository.findByAggregateId(c.toString()) }
        assertThat(full.map { it.eventType }).containsExactlyInAnyOrder(
            "PARTY_CREATED", // A's own creation
            "PARTY_MERGED", // A -> B
            "PARTY_CREATED", // B's own creation
            "PARTY_MERGED", // B -> C
            "KYC_STATUS_CHANGED", // C, post-chain
        )
    }

    /**
     * A Kafka redelivery of the same `PARTY_MERGED` message must not fail, and must not double
     * the retired party's rows in a history query — the natural idempotency key is the retired
     * party id itself (party-service's `mergeParty` rejects merging an already-MERGED party, so a
     * given retired id is the source of at most one real merge, ever).
     */
    @Test
    fun `redelivering the same PARTY_MERGED event is idempotent`() {
        val retired = UUID.randomUUID()
        val survivor = UUID.randomUUID()

        onEventLoop {
            val c = consumerFor()
            c.consume(partyLifecycleEvent("PARTY_CREATED", retired, "2026-01-01T09:00:00Z"))
            val mergedPayload = partyMergedEvent(retired, survivor, "2026-01-02T09:00:00Z")
            c.consume(mergedPayload)
            c.consume(mergedPayload) // redelivery
        }

        // Each delivery still lands its own audit_entries row (append-only, by design) — but the
        // merge-index resolution set is not doubled.
        val ancestors = onEventLoop { mergeIndex.ancestorsOf(survivor.toString()) }
        assertThat(ancestors).containsExactlyInAnyOrder(survivor.toString(), retired.toString())

        // Not containsExactly: the two PARTY_MERGED rows share an identical occurredAt (same
        // payload, redelivered), so their relative order under `ORDER BY occurredAt DESC` alone
        // is not guaranteed — only the total shape (both merges, plus the one pre-merge row) is.
        val full = onEventLoop { repository.findByAggregateId(survivor.toString()) }
        assertThat(full.map { it.eventType }).containsExactlyInAnyOrder("PARTY_MERGED", "PARTY_MERGED", "PARTY_CREATED")
    }

    /** An id nothing was ever merged into behaves exactly as before — the common case. */
    @Test
    fun `a party that was never merged sees only its own rows`() {
        val party = UUID.randomUUID()
        onEventLoop {
            consumerFor().consume(partyLifecycleEvent("PARTY_CREATED", party, "2026-01-01T09:00:00Z"))
        }

        assertThat(onEventLoop { repository.findByAggregateId(party.toString()) }).hasSize(1)
        assertThat(onEventLoop { mergeIndex.ancestorsOf(party.toString()) }).containsExactly(party.toString())
    }
}
