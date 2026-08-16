// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.proposal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.ProposalDecisionPhase
import com.openbank.analytics.application.port.out.ProposalStore
import com.openbank.analytics.infrastructure.clickhouse.ClickHouseClient
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.IngestSource
import com.openbank.libs.analytics.Proposal
import com.openbank.libs.analytics.ProposalState
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * ClickHouse-backed [ProposalStore]: the durable maker-checker decision trail (ADR-0023, F3).
 *
 * Each state transition is written as a new row into `reload_proposals`; the table's
 * `ReplacingMergeTree(updated_at)` keeps the latest transition, and reads use `FINAL` so a proposal's
 * **current** state (and full lineage of who proposed / approved / executed) survives a restart and
 * is queryable as audit evidence. The segregation-of-duties *logic* lives in the [Proposal] state
 * machine and the atomic [claim] below; this class makes the resulting trail durable rather than
 * in-memory. See [claim]'s KDoc for exactly what its compare-and-set does and does not cover.
 *
 * It is the `@Alternative @Priority(100)` binding behind the `@Default` [InMemoryProposalStore],
 * gated at build time by `openbank.analytics.sink.type=clickhouse`. Row building and parsing are pure
 * and unit-tested without a server.
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "openbank.analytics.sink.type", stringValue = "clickhouse")
open class ClickHouseProposalStore : ProposalStore {

    @Inject
    lateinit var clickhouse: ClickHouseClient

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    /**
     * The compare-and-set primitive for [claim], scoped to THIS POD.
     *
     * ClickHouse's `ReplacingMergeTree` (what `reload_proposals` uses, see below) has no conditional
     * write: an `INSERT` is always accepted, and `FINAL`/the ReplacingMergeTree merge only decide
     * which row wins *after the fact*, by `updated_at` — so a `get`-then-`save` pair here has the
     * exact race this class exists to close (two concurrent decisions both read `PROPOSED`, both
     * pass the domain's state check, both `save`). `ALTER TABLE ... UPDATE ... WHERE state = :from`
     * (the Postgres pattern `CompliancePackActivationRepository.compareAndSetDecision` uses) is not
     * an answer either: ClickHouse mutations are asynchronous background jobs with no synchronous
     * "rows affected" result, so a caller cannot learn whether it won.
     *
     * The one primitive that WOULD give a true, cross-replica compare-and-set — the `KeeperMap`
     * table engine, backed by ClickHouse Keeper — needs a Keeper ensemble this deployment does not
     * have: `clickhouse.yaml` runs a single, unreplicated ClickHouse node and says so explicitly
     * ("production would run a replicated ClickHouse (Keeper + shards)"). Verified directly against
     * that same image (`clickhouse/clickhouse-server:24.8-alpine`) before writing this comment:
     * `CREATE TABLE ... ENGINE = KeeperMap(...)` fails with `KeeperMap is disabled because
     * 'keeper_map_path_prefix' config is not defined` — the engine is not merely undocumented here,
     * it is actively unavailable, so depending on it would ship a claim that silently never works.
     *
     * What IS true today, and is what this closes: `analytics-sink` runs a single replica
     * (`analytics-sink.yaml`, `replicas: 1`), so a JVM-local claim — the same
     * `ConcurrentHashMap`-backed compare-and-set [InMemoryProposalStore] uses — closes the race for
     * every decision this deployment can actually receive. It does NOT protect a decision race split
     * across multiple pods; scaling this deployment beyond one replica needs a durable claim first
     * (KeeperMap once ClickHouse Keeper is deployed, or an equivalent). Track that as a precondition
     * of raising `replicas`, not as something this class already provides.
     */
    private val claims = ConcurrentHashMap.newKeySet<String>()

    override suspend fun save(proposal: Proposal<BackfillRequest>) {
        clickhouse.insert("reload_proposals", rowJson(proposal, Instant.now(clock)))
    }

    override suspend fun claim(id: String, phase: ProposalDecisionPhase): Boolean = claims.add("$id:$phase")

    override suspend fun get(id: String): Proposal<BackfillRequest>? {
        val sql = "SELECT $COLUMNS FROM reload_proposals FINAL WHERE proposal_id = '${escape(id)}' FORMAT JSONEachRow"
        return clickhouse.query(sql).lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.let { parseRow(mapper.readTree(it)) }
    }

    override suspend fun list(): List<Proposal<BackfillRequest>> {
        val sql = "SELECT $COLUMNS FROM reload_proposals FINAL ORDER BY proposed_at FORMAT JSONEachRow"
        return clickhouse.query(sql).lineSequence().filter { it.isNotBlank() }
            .map { parseRow(mapper.readTree(it)) }.toList()
    }

    /** Serialises a proposal (+ its wrapped [BackfillRequest]) into a `reload_proposals` row. Pure. */
    internal fun rowJson(p: Proposal<BackfillRequest>, updatedAt: Instant): String {
        val a = p.action
        val row = linkedMapOf<String, Any?>(
            "proposal_id" to p.id,
            "state" to p.state.name,
            "ingest_source" to a.source.name,
            "range_from" to DT.format(a.from),
            "range_to" to DT.format(a.to),
            "aggregate_type" to a.aggregateType,
            "aggregate_id" to a.aggregateId,
            "requested_by" to a.requestedBy,
            "proposed_by" to p.proposedBy,
            "proposed_at" to DT.format(p.proposedAt),
            "decided_by" to p.decidedBy,
            "decided_at" to p.decidedAt?.let { DT.format(it) },
            "decision_reason" to p.decisionReason,
            "executed_at" to p.executedAt?.let { DT.format(it) },
            "reason" to a.reason,
            "updated_at" to DT.format(updatedAt),
        )
        return mapper.writeValueAsString(row)
    }

    /** Reconstructs a [Proposal] from a `reload_proposals` JSON row. Pure / unit-testable. */
    internal fun parseRow(n: JsonNode): Proposal<BackfillRequest> {
        val request = BackfillRequest(
            source = IngestSource.valueOf(n.get("ingest_source").asText()),
            from = parseDt(n.get("range_from").asText()),
            to = parseDt(n.get("range_to").asText()),
            aggregateType = n.textOrNull("aggregate_type"),
            aggregateId = n.textOrNull("aggregate_id"),
            reason = n.get("reason").asText(),
            requestedBy = n.get("requested_by").asText(),
        )
        return Proposal(
            id = n.get("proposal_id").asText(),
            action = request,
            proposedBy = n.get("proposed_by").asText(),
            proposedAt = parseDt(n.get("proposed_at").asText()),
            state = ProposalState.valueOf(n.get("state").asText()),
            decidedBy = n.textOrNull("decided_by"),
            decidedAt = n.textOrNull("decided_at")?.let { parseDt(it) },
            decisionReason = n.textOrNull("decision_reason"),
            executedAt = n.textOrNull("executed_at")?.let { parseDt(it) },
        )
    }

    private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()

    private fun parseDt(text: String): Instant = LocalDateTime.parse(text.trim(), DT_PARSE).toInstant(ZoneOffset.UTC)

    private fun escape(s: String): String = s.replace("'", "''")

    private companion object {
        const val COLUMNS =
            "proposal_id, state, ingest_source, range_from, range_to, aggregate_type, aggregate_id, " +
                "requested_by, proposed_by, proposed_at, decided_by, decided_at, decision_reason, executed_at, reason"

        val DT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
        val DT_PARSE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")
    }
}
