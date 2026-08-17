// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.clickhouse

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.IntegrityAnchor
import com.openbank.analytics.application.port.out.ProposalDecisionPhase
import com.openbank.analytics.infrastructure.proposal.ClickHouseProposalStore
import com.openbank.analytics.infrastructure.reconcile.ClickHouseWarehouseStateReader
import com.openbank.analytics.infrastructure.worm.ClickHouseWormArchive
import com.openbank.libs.analytics.AggregateKey
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.IngestSource
import com.openbank.libs.analytics.Proposal
import com.openbank.libs.analytics.ProposalState
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

/**
 * Plain-JUnit tests for the ClickHouse-native adapters (reconciliation reader, WORM mirror, durable
 * proposal store). A [FakeClickHouseClient] captures inserts and stubs query responses, so the SQL
 * building and result parsing are verified exactly — no ClickHouse server, module stays offline.
 */
class ClickHouseAdaptersTest {

    private val mapper = ObjectMapper()

    private class FakeClickHouseClient : ClickHouseClient() {
        var lastInsertTable: String? = null
        var lastInsertBody: String? = null
        var lastQuery: String? = null
        var queryResponse: String = ""

        override suspend fun insert(table: String, jsonEachRow: String) {
            lastInsertTable = table
            lastInsertBody = jsonEachRow
        }

        override suspend fun query(sql: String): String {
            lastQuery = sql
            return queryResponse
        }
    }

    // ------------------------------------------------------------------ WarehouseStateReader (F4/F5)

    @Test
    fun `warehouse reader parses max-version rows into AggregateKey map`() = runBlocking<Unit> {
        val client = FakeClickHouseClient().apply {
            queryResponse = "ACCOUNT\tacc-1\t5\nPARTY\tp-9\t2\n"
        }
        val reader = ClickHouseWarehouseStateReader().apply { clickhouse = client }

        val versions = reader.currentVersions()

        assertThat(versions).containsEntry(AggregateKey("ACCOUNT", "acc-1"), 5L)
        assertThat(versions).containsEntry(AggregateKey("PARTY", "p-9"), 2L)
        assertThat(client.lastQuery).contains("max(aggregate_version)")
    }

    @Test
    fun `warehouse reader parses per-type counts`() = runBlocking<Unit> {
        val client = FakeClickHouseClient().apply { queryResponse = "ACCOUNT\t12\nTRANSACTION\t340\n" }
        val reader = ClickHouseWarehouseStateReader().apply { clickhouse = client }

        assertThat(reader.rowCountsByType()).containsEntry("ACCOUNT", 12L).containsEntry("TRANSACTION", 340L)
        assertThat(client.lastQuery).contains("count(DISTINCT aggregate_id)")
    }

    @Test
    fun `warehouse reader parses the per-aggregate version sequence for completeness`() = runBlocking<Unit> {
        val client = FakeClickHouseClient().apply { queryResponse = "ACCOUNT\tacc-1\t1,2,4\n" }
        val reader = ClickHouseWarehouseStateReader().apply { clickhouse = client }

        val byAgg = reader.versionsByAggregate()

        assertThat(byAgg[AggregateKey("ACCOUNT", "acc-1")]).containsExactly(1L, 2L, 4L)
        assertThat(client.lastQuery).contains("groupUniqArray(aggregate_version)")
    }

    /**
     * Case fold, issue #4604 (the #4553 follow-up). AggregateKey's equals/hashCode are
     * case-sensitive, and bronze holds both `ACCOUNT`/`Account` for the same account — an unfolded
     * GROUP BY would produce two AggregateKeys for one real aggregate, which ReconciliationJob
     * would compare against ReconciliationSource's own (consistently-cased) key and report as BOTH a
     * version mismatch and an orphan. Measured on the sandbox warehouse before this fix: account
     * `28ed9683-…` split into `(ACCOUNT, maxVersion=0)` and `(Account, maxVersion=1)` — the true
     * max (1) invisible under the `ACCOUNT` key a source-of-truth service would report.
     *
     * A unit test with a faked query RESPONSE cannot exercise ClickHouse's own GROUP BY merge — only
     * asserting the emitted SQL asks for it is possible at this layer; the merge itself was verified
     * directly against the sandbox warehouse before writing this fix, not assumed.
     */
    @Test
    fun `all three warehouse queries fold aggregate_type case, so one aggregate cannot become two keys`() {
        val client = FakeClickHouseClient()
        val reader = ClickHouseWarehouseStateReader().apply { clickhouse = client }
        runBlocking {
            reader.currentVersions()
            assertThat(client.lastQuery).contains("upper(aggregate_type)")
            assertThat(client.lastQuery).doesNotContain("GROUP BY aggregate_type,")

            reader.rowCountsByType()
            assertThat(client.lastQuery).contains("upper(aggregate_type)")
            assertThat(client.lastQuery).doesNotContain("GROUP BY aggregate_type ")

            reader.versionsByAggregate()
            assertThat(client.lastQuery).contains("upper(aggregate_type)")
            assertThat(client.lastQuery).doesNotContain("GROUP BY aggregate_type,")
        }
    }

    @Test
    fun `warehouse reader tolerates blank and malformed lines`() = runBlocking<Unit> {
        val client = FakeClickHouseClient().apply { queryResponse = "\nACCOUNT\tacc-1\t5\nBROKEN_LINE\n" }
        val reader = ClickHouseWarehouseStateReader().apply { clickhouse = client }

        assertThat(reader.currentVersions()).hasSize(1)
    }

    // ------------------------------------------------------------------------- WORM mirror (F1/F2)

    @Test
    fun `worm anchor serialises null previous-hash as JSON null`() {
        val worm = ClickHouseWormArchive().apply { mapper = this@ClickHouseAdaptersTest.mapper }
        val anchor = IntegrityAnchor("a1", "rootHash", null, 3, "BACKFILL", Instant.parse("2026-01-01T00:00:00Z"))

        val node = mapper.readTree(worm.anchorJson(anchor))

        assertThat(node.get("anchor_id").asText()).isEqualTo("a1")
        assertThat(node.get("merkle_root").asText()).isEqualTo("rootHash")
        assertThat(node.get("previous_anchor_hash").isNull).isTrue()
        assertThat(node.get("record_count").asInt()).isEqualTo(3)
        assertThat(node.get("sealed_at").asText()).isEqualTo("2026-01-01 00:00:00.000")
    }

    @Test
    fun `worm seal inserts into integrity_anchors`() = runBlocking<Unit> {
        val client = FakeClickHouseClient()
        val worm = ClickHouseWormArchive().apply {
            clickhouse = client
            mapper = this@ClickHouseAdaptersTest.mapper
        }

        worm.seal(IntegrityAnchor("a1", "r", "prev", 1, "RECONCILIATION:x", Instant.parse("2026-01-01T00:00:00Z")))

        assertThat(client.lastInsertTable).isEqualTo("integrity_anchors")
        assertThat(client.lastInsertBody).contains("\"previous_anchor_hash\":\"prev\"")
    }

    @Test
    fun `worm latest parses a row and decodes the null sentinel`() = runBlocking<Unit> {
        val client = FakeClickHouseClient().apply {
            queryResponse = "a1\trootHash\t\\N\t7\tBACKFILL\t2026-01-01 00:00:00.000\n"
        }
        val worm = ClickHouseWormArchive().apply {
            clickhouse = client
            mapper = this@ClickHouseAdaptersTest.mapper
        }

        val latest = worm.latest()!!

        assertThat(latest.anchorId).isEqualTo("a1")
        assertThat(latest.previousAnchorHash).isNull()
        assertThat(latest.recordCount).isEqualTo(7)
        assertThat(latest.sealedAt).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
    }

    @Test
    fun `worm latest is null when the chain is empty`() = runBlocking<Unit> {
        val client = FakeClickHouseClient().apply { queryResponse = "" }
        val worm = ClickHouseWormArchive().apply {
            clickhouse = client
            mapper = this@ClickHouseAdaptersTest.mapper
        }

        assertThat(worm.latest()).isNull()
    }

    // ------------------------------------------------------------------ durable ProposalStore (F3)

    private fun sampleProposal(): Proposal<BackfillRequest> {
        val request = BackfillRequest(
            source = IngestSource.CORRECTION,
            from = Instant.parse("2025-01-01T00:00:00Z"),
            to = Instant.parse("2025-01-02T00:00:00Z"),
            aggregateType = "ACCOUNT",
            aggregateId = "acc-1",
            reason = "fix restated balance",
            requestedBy = "alice",
        )
        return Proposal(
            id = "prop-1",
            action = request,
            proposedBy = "alice",
            proposedAt = Instant.parse("2026-05-01T10:00:00Z"),
            state = ProposalState.APPROVED,
            decidedBy = "bob",
            decidedAt = Instant.parse("2026-05-01T11:00:00Z"),
            decisionReason = "looks right",
        )
    }

    @Test
    fun `proposal row round-trips through rowJson and parseRow`() {
        val store = ClickHouseProposalStore().apply { mapper = this@ClickHouseAdaptersTest.mapper }
        val original = sampleProposal()

        val json = store.rowJson(original, Instant.parse("2026-05-01T11:00:00Z"))
        val parsed = store.parseRow(mapper.readTree(json))

        assertThat(parsed.id).isEqualTo("prop-1")
        assertThat(parsed.state).isEqualTo(ProposalState.APPROVED)
        assertThat(parsed.proposedBy).isEqualTo("alice")
        assertThat(parsed.decidedBy).isEqualTo("bob")
        assertThat(parsed.decisionReason).isEqualTo("looks right")
        assertThat(parsed.action.source).isEqualTo(IngestSource.CORRECTION)
        assertThat(parsed.action.aggregateType).isEqualTo("ACCOUNT")
        assertThat(parsed.action.requestedBy).isEqualTo("alice")
        assertThat(parsed.action.reason).isEqualTo("fix restated balance")
        assertThat(parsed.action.from).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"))
        assertThat(parsed.executedAt).isNull()
    }

    @Test
    fun `proposal with no aggregate scope reconstructs nullable fields as null`() {
        val store = ClickHouseProposalStore().apply { mapper = this@ClickHouseAdaptersTest.mapper }
        val request = BackfillRequest(
            source = IngestSource.BACKFILL,
            from = Instant.parse("2025-01-01T00:00:00Z"),
            to = Instant.parse("2025-02-01T00:00:00Z"),
            reason = "gap fill",
            requestedBy = "carol",
        )
        val proposal = Proposal(
            id = "p2",
            action = request,
            proposedBy = "carol",
            proposedAt = Instant.parse("2026-05-01T10:00:00Z"),
        )

        val parsed = store.parseRow(mapper.readTree(store.rowJson(proposal, Instant.now())))

        assertThat(parsed.action.aggregateType).isNull()
        assertThat(parsed.action.aggregateId).isNull()
        assertThat(parsed.decidedBy).isNull()
        assertThat(parsed.state).isEqualTo(ProposalState.PROPOSED)
    }

    @Test
    fun `proposal save inserts into reload_proposals and get parses FINAL response`() = runBlocking<Unit> {
        val client = FakeClickHouseClient()
        val store = ClickHouseProposalStore().apply {
            clickhouse = client
            mapper = this@ClickHouseAdaptersTest.mapper
            clock = Clock.systemUTC()
        }
        store.save(sampleProposal())
        assertThat(client.lastInsertTable).isEqualTo("reload_proposals")

        client.queryResponse = client.lastInsertBody!! // echo the row back as a JSONEachRow result
        val fetched = store.get("prop-1")!!

        assertThat(fetched.id).isEqualTo("prop-1")
        assertThat(fetched.action.aggregateId).isEqualTo("acc-1")
        assertThat(client.lastQuery).contains("FINAL").contains("prop-1")
    }

    @Test
    fun `claim wins exactly once per (id, phase) and never touches ClickHouse`() = runBlocking<Unit> {
        val client = FakeClickHouseClient()
        val store = ClickHouseProposalStore().apply { clickhouse = client }

        assertThat(store.claim("prop-1", ProposalDecisionPhase.DECIDE)).isTrue()
        // Same (id, phase) again — the negative case: a second claim on an already-claimed phase
        // must be refused, not silently accepted.
        assertThat(store.claim("prop-1", ProposalDecisionPhase.DECIDE)).isFalse()
        // A different phase on the same proposal is independent.
        assertThat(store.claim("prop-1", ProposalDecisionPhase.EXECUTE)).isTrue()
        // A different proposal is independent too.
        assertThat(store.claim("prop-2", ProposalDecisionPhase.DECIDE)).isTrue()

        assertThat(client.lastInsertTable).isNull()
        assertThat(client.lastQuery).isNull()
    }
}
