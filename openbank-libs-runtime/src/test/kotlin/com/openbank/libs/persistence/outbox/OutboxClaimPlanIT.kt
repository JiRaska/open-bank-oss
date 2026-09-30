// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.OpenBaoTransitFieldProtectorIT.Companion.requireDocker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The plan gate (ADR-0327 D12): the kernel's own D3 claim statement, anti-joins included, against
 * the canonical outbox DDL with a SENT-dominated table, must be served by the partial indexes —
 * **no `Seq Scan` node and `shared hit + read < 500` buffers** — so the finding-5 collapse (Seq
 * Scan + external sort, 163 k buffers, 4–8 s per claim) cannot come back without a red PR in this
 * module's own CI.
 *
 * **What the 500-buffer bound is measured on.** The statement is an `UPDATE … RETURNING`, so its
 * top node is `ModifyTable`, and that node's own buffers are the 25 row writes fanned out over
 * every index on the table (measured 2026-09-30: ~594 buffers for 25 rows with the six indexes a
 * Phase-1 table carries — pk, `event_id`, the OLD `(status, created_at)`, `(aggregate_id)` and the
 * two new partials). That cost scales with `batch × index count`, never with table size, and the
 * old `(status, created_at)` index only leaves in Phase 4. Finding 5 is about the SEARCH — the Seq
 * Scan + sort over the SENT majority — so the buffer bound applies to the claim's search subtree
 * (the child of `ModifyTable`: 209 buffers measured, 83 in the ADR's SELECT-only Appendix B), and
 * the no-Seq-Scan rule applies to the whole statement. Both totals are printed on every run.
 *
 * Two halves, in this order, because a gate that has only ever passed is unfalsified:
 *  1. **Negative** — the same statement against the OLD index shape (`(status, created_at)` +
 *     `(aggregate_id)`, i.e. ledger's V2 + V18 + V24 exactly) must VIOLATE the criteria. If this
 *     stops failing, the criteria have gone vacuous, not the plan good.
 *  2. **Positive** — after applying `db/outbox-v2-template.sql` (the very file a service copies),
 *     the same statement must satisfy them.
 *
 * Plain JDBC on a Testcontainers Postgres: `EXPLAIN` needs no Hibernate, and the statement text
 * is [OutboxSql.claim] verbatim with its named parameters inlined as literals — the only thing a
 * JDBC driver cannot bind. Docker missing: SKIPPED locally, FAILS when `CI=true`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OutboxClaimPlanIT {

    private lateinit var pg: GenericContainer<*>
    private lateinit var conn: Connection
    private val mapper = ObjectMapper()
    private val shape = OutboxTableShape("ledger_outbox")

    @BeforeAll fun start() {
        requireDocker(DockerClientFactory.instance().isDockerAvailable, System.getenv("CI"))
        pg = GenericContainer(DockerImageName.parse(IMAGE))
            .withEnv("POSTGRES_USER", "ob").withEnv("POSTGRES_PASSWORD", "ob").withEnv("POSTGRES_DB", "ob")
            .withExposedPorts(PG_PORT)
            .waitingFor(Wait.forListeningPort())
        pg.start()
        val url = "jdbc:postgresql://${pg.host}:${pg.getMappedPort(PG_PORT)}/ob"
        // The container reports its port before postgres accepts logins on the first boot; retry briefly.
        var attempt = 0
        while (true) {
            try {
                conn = DriverManager.getConnection(url, "ob", "ob")
                break
            } catch (ex: java.sql.SQLException) {
                if (++attempt > CONNECT_ATTEMPTS) throw ex
                Thread.sleep(CONNECT_BACKOFF_MS)
            }
        }
        conn.autoCommit = true
        conn.createStatement().use { st ->
            // Ledger's V2 + V18 + V24 — the canonical DDL shape before ADR-0327 D2.
            st.execute(
                """
                CREATE TABLE ledger_outbox (
                    id BIGSERIAL PRIMARY KEY,
                    event_id UUID NOT NULL UNIQUE,
                    aggregate_id UUID NOT NULL,
                    event_type VARCHAR(128) NOT NULL,
                    payload TEXT NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    sent_at TIMESTAMPTZ,
                    last_error TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                CREATE INDEX idx_ledger_outbox_status_created_at ON ledger_outbox(status, created_at ASC);
                CREATE INDEX idx_ledger_outbox_aggregate_id ON ledger_outbox(aggregate_id);
                ALTER TABLE ledger_outbox ADD COLUMN claimed_at TIMESTAMPTZ;
                ALTER TABLE ledger_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
                """.trimIndent(),
            )
            // ADR-0327 Appendix B seed: a SENT majority and a pending tail, distinct aggregates.
            st.execute(
                """
                INSERT INTO ledger_outbox (event_id, aggregate_id, event_type, payload, status, sent_at, created_at, updated_at)
                SELECT gen_random_uuid(), gen_random_uuid(), 'x', '{}', 'SENT', now(), now() - (g || ' seconds')::interval, now()
                FROM generate_series(1, $SENT_ROWS) g;
                INSERT INTO ledger_outbox (event_id, aggregate_id, event_type, payload, status, created_at, updated_at)
                SELECT gen_random_uuid(), gen_random_uuid(), 'x', '{}', 'PENDING', now(), now() FROM generate_series(1, $PENDING_ROWS);
                """.trimIndent(),
            )
            // The claim's own predicate needs the column even before D2 lands; the OLD shape has it
            // nowhere, which is the point of the negative half — but a missing column is an error,
            // not a plan, so add the column alone and leave the indexes as they were.
            st.execute("ALTER TABLE ledger_outbox ADD COLUMN next_attempt_at TIMESTAMPTZ")
            st.execute("ANALYZE ledger_outbox")
        }
    }

    @AfterAll fun stop() {
        if (::conn.isInitialized) conn.close()
        if (::pg.isInitialized) pg.stop()
    }

    @Test
    @Order(1)
    fun `NEGATIVE - against the old index shape the claim plan violates the gate`() {
        val plan = explainClaim()
        val violations = gateViolations(plan)
        report("old-index-shape", plan, violations)
        assertThat(violations)
            .describedAs("the gate must be able to fail; with the old (status, created_at) index it must. Plan:\n%s", plan.toPrettyString())
            .isNotEmpty()
    }

    @Test
    @Order(2)
    fun `POSITIVE - after the D2 template migration the claim search is index-served under 500 buffers`() {
        val template = checkNotNull(javaClass.getResource("/db/outbox-v2-template.sql")) { "template missing" }
            .readText()
            .replace("<t>", "ledger")
        conn.createStatement().use { st ->
            st.execute(template)
            st.execute("ANALYZE ledger_outbox")
        }
        val plan = explainClaim()
        val violations = gateViolations(plan)
        report("d2-template-shape", plan, violations)
        assertThat(violations)
            .describedAs("claim plan after D2 must be index-served. Plan:\n%s", plan.toPrettyString())
            .isEmpty()
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` over [OutboxSql.claim] inside a rolled-back transaction. */
    private fun explainClaim(): JsonNode {
        val now = Instant.now()
        val sql = bind(
            OutboxSql.claim(shape),
            mapOf(
                "pending" to OutboxStatus.PENDING.name,
                "failed" to OutboxStatus.FAILED.name,
                "dispatching" to OutboxStatus.DISPATCHING.name,
                "now" to now.toString(),
                "stale" to now.minus(Duration.ofMinutes(2)).toString(),
                "limit" to OutboxDispatch.DEFAULT_BATCH_SIZE,
            ),
        )
        conn.autoCommit = false
        try {
            conn.createStatement().use { st ->
                st.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql").use { rs ->
                    check(rs.next())
                    return mapper.readTree(rs.getString(1))[0]["Plan"]
                }
            }
        } finally {
            conn.rollback()
            conn.autoCommit = true
        }
    }

    /**
     * Inline named parameters as SQL literals for EXPLAIN — the statement text itself is the
     * repository's, unchanged. Longest names first so `:now` never clips `:nowish`-style names.
     */
    private fun bind(sql: String, params: Map<String, Any>): String =
        params.entries.sortedByDescending { it.key.length }.fold(sql) { acc, (name, value) ->
            val literal = when (value) {
                is Number -> value.toString()
                is UUID -> "'$value'::uuid"
                else -> "'${value.toString().replace("'", "''")}'::timestamptz".takeIf { name == "now" || name == "stale" }
                    ?: "'${value.toString().replace("'", "''")}'"
            }
            acc.replace(Regex(":$name\\b"), literal)
        }

    private fun nodes(plan: JsonNode): Sequence<JsonNode> = sequence {
        yield(plan)
        plan["Plans"]?.forEach { child -> yieldAll(nodes(child)) }
    }

    private fun report(shapeLabel: String, plan: JsonNode, violations: List<String>) {
        val (hit, read) = sharedBuffers(plan)
        val (sHit, sRead) = sharedBuffers(searchSubtree(plan))
        println(
            "outbox-claim-plan[$shapeLabel]: statement shared hit+read=${hit + read} (search subtree ${sHit + sRead}, " +
                "row writes ${hit + read - sHit - sRead}) seqscan=${hasSeqScan(plan)} " +
                "gate(no Seq Scan anywhere; search subtree < $BUFFER_LIMIT) -> ${if (violations.isEmpty()) "PASS" else violations}",
        )
    }

    /** The claim's search: the child of the top `ModifyTable` node (the statement itself when there is none). */
    private fun searchSubtree(plan: JsonNode): JsonNode =
        if (plan["Node Type"]?.asText() == "ModifyTable") plan["Plans"]?.firstOrNull() ?: plan else plan

    private fun hasSeqScan(plan: JsonNode): Boolean = nodes(plan).any { it["Node Type"]?.asText() == "Seq Scan" }

    /** Top-node buffer totals include every child (Postgres accumulates them upward). */
    private fun sharedBuffers(plan: JsonNode): Pair<Long, Long> =
        (plan["Shared Hit Blocks"]?.asLong() ?: 0L) to (plan["Shared Read Blocks"]?.asLong() ?: 0L)

    private fun gateViolations(plan: JsonNode): List<String> {
        val out = mutableListOf<String>()
        if (hasSeqScan(plan)) out += "plan contains a Seq Scan node"
        val (hit, read) = sharedBuffers(searchSubtree(plan))
        if (hit + read >= BUFFER_LIMIT) out += "claim search shared buffers hit+read = ${hit + read} >= $BUFFER_LIMIT"
        return out
    }

    private companion object {
        const val IMAGE = "postgres:18.6-alpine" // the fleet's PostgresBase image (openbank-libs-testing)
        const val PG_PORT = 5432
        const val SENT_ROWS = 200_000
        const val PENDING_ROWS = 50_000
        const val BUFFER_LIMIT = 500L
        const val CONNECT_ATTEMPTS = 30
        const val CONNECT_BACKOFF_MS = 500L
    }
}
