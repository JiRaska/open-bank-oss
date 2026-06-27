// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.proposal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * ClickHouse-backed [ProposalStore]: the durable maker-checker decision trail (ADR-0023, F3).
 *
 * Each state transition is written as a new row into `reload_proposals`; the table's
 * `ReplacingMergeTree(updated_at)` keeps the latest transition, and reads use `FINAL` so a proposal's
 * **current** state (and full lineage of who proposed / approved / executed) survives a restart and
 * is queryable as audit evidence. This makes F3 fully GREEN: the segregation-of-duties *logic* was
 * already real (the Proposal state machine), and now the trail is durable rather than in-memory.
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

    override suspend fun save(proposal: Proposal<BackfillRequest>) {
        clickhouse.insert("reload_proposals", rowJson(proposal, Instant.now(clock)))
    }

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
