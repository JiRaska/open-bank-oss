// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sanctions.application.port.out.SanctionsChangePublisher
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import com.openbank.sanctions.application.port.out.SanctionsPublicationOutcome
import com.openbank.sanctions.domain.model.SanctionsListType
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Exercise the migration-installed trigger through the same independent PgPool used by imports. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsChangeJournalIT {
    @Inject
    lateinit var pool: PgPool

    @Inject
    lateinit var publisher: SanctionsChangePublisher

    @Inject
    lateinit var outbox: SanctionsOutboxRepository

    @Inject
    lateinit var mapper: ObjectMapper

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun execute(sql: String) {
        onEventLoop { pool.query(sql).execute().awaitSuspending() }
    }

    private fun journalCount(): Long = onEventLoop {
        pool.query("SELECT count(*) AS total FROM sanctions_change_journal").execute()
            .awaitSuspending().iterator().next().getLong("total")
    }

    private fun unresolvedJournalCount(): Long = onEventLoop {
        pool.query("SELECT count(*) AS total FROM sanctions_change_journal WHERE resolved_at IS NULL").execute()
            .awaitSuspending().iterator().next().getLong("total")
    }

    @BeforeEach
    fun clearEntriesAndJournal() {
        execute("DELETE FROM sanctions_entries")
        execute("DELETE FROM sanctions_change_journal")
        execute("DELETE FROM sanctions_outbox")
        execute("UPDATE sanctions_change_publication SET last_storm_fingerprint = NULL")
    }

    @Test
    fun `independent writes retain changes and rollback retains neither content nor evidence`() {
        execute(INSERT_ENTRY)
        assertThat(journalCount()).isEqualTo(1)
        assertThatThrownBy {
            onEventLoop {
                pool.withTransaction { connection ->
                    connection.query("UPDATE sanctions_entries SET primary_name = 'Rolled back'")
                        .execute().chain { _ ->
                            connection.query("SELECT 1 / 0").execute()
                        }
                }.awaitSuspending()
            }
        }.hasMessageContaining("division by zero")
        assertThat(journalCount()).isEqualTo(1)
        val name = onEventLoop {
            pool.query("SELECT primary_name FROM sanctions_entries").execute()
                .awaitSuspending().iterator().next().getString("primary_name")
        }
        assertThat(name).isEqualTo("Example Person")
    }

    @Test
    fun `reordering aliases is unchanged but a search value change is journaled`() {
        execute(INSERT_ENTRY)
        execute(
            """
                UPDATE sanctions_entries
                SET aliases_json = '["Alias B", "Alias A", "Alias A"]',
                    search_text = 'example person | alias b | alias a'
            """.trimIndent(),
        )
        assertThat(journalCount()).isEqualTo(1)
        execute("UPDATE sanctions_entries SET search_text = 'different searchable content'")
        assertThat(journalCount()).isEqualTo(2)
    }

    @Test
    fun `deactivation reactivation and deletion retain every transition`() {
        execute(INSERT_ENTRY)
        execute("UPDATE sanctions_entries SET active = false")
        execute("UPDATE sanctions_entries SET active = true")
        execute("DELETE FROM sanctions_entries")
        val states = onEventLoop {
            pool.query("SELECT active FROM sanctions_change_journal ORDER BY id").execute()
                .awaitSuspending().map { it.getBoolean("active") }
        }
        assertThat(states).containsExactly(true, false, true, false)
    }

    @Test
    fun `a malformed legacy value can be repaired`() {
        execute(INSERT_ENTRY.replace("'[\"Alias A\", \"Alias B\"]'", "'malformed legacy value'"))
        execute("UPDATE sanctions_entries SET aliases_json = '[]'")
        assertThat(journalCount()).isEqualTo(2)
    }

    @Test
    fun `a legacy nonarray value does not prevent deactivation`() {
        execute(INSERT_ENTRY.replace("'[\"Alias A\", \"Alias B\"]'", "'{}'"))
        execute("UPDATE sanctions_entries SET active = false")
        assertThat(journalCount()).isEqualTo(2)
    }

    @Test
    fun `legacy JSON with an unsupported Unicode escape can be repaired`() {
        val legacy = """["\u0000"]"""
        execute(INSERT_ENTRY.replace("'[\"Alias A\", \"Alias B\"]'", "'$legacy'"))
        execute("UPDATE sanctions_entries SET aliases_json = '[]'")
        assertThat(journalCount()).isEqualTo(2)
    }

    private fun publish(listType: SanctionsListType = SanctionsListType.PEP_GLOBAL): SanctionsPublicationOutcome =
        onEventLoop {
            val id = pool.preparedQuery("SELECT id FROM sanctions_lists WHERE list_type = $1")
                .execute(io.vertx.mutiny.sqlclient.Tuple.of(listType.name))
                .awaitSuspending().iterator().next().getUUID("id")
            publisher.publishPending(id, listType)
        }

    private fun eventCount(type: String): Long = onEventLoop {
        pool.preparedQuery("SELECT count(*) AS total FROM sanctions_outbox WHERE event_type = $1")
            .execute(io.vertx.mutiny.sqlclient.Tuple.of(type))
            .awaitSuspending().iterator().next().getLong("total")
    }

    @Test
    fun `failed outbox commit retains journal and identical retry publishes once`() {
        execute(INSERT_ENTRY)
        execute(
            "ALTER TABLE sanctions_outbox ADD CONSTRAINT reject_change_for_test " +
                "CHECK (event_type <> 'SANCTIONS_LIST_CHANGED') NOT VALID",
        )
        try {
            assertThatThrownBy { publish() }.hasMessageContaining("reject_change_for_test")
            assertThat(journalCount()).isEqualTo(1)
            assertThat(eventCount("SANCTIONS_LIST_CHANGED")).isZero()
        } finally {
            execute("ALTER TABLE sanctions_outbox DROP CONSTRAINT reject_change_for_test")
        }
        execute("UPDATE sanctions_entries SET primary_name = primary_name")
        assertThat(journalCount()).isEqualTo(1)
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.PUBLISHED)
        assertThat(journalCount()).isZero()
        assertThat(eventCount("SANCTIONS_LIST_CHANGED")).isEqualTo(1)
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.NO_CHANGES)
        assertThat(eventCount("SANCTIONS_LIST_CHANGED")).isEqualTo(1)
    }

    @Test
    fun `storm retains evidence and emits one signal for identical retry`() {
        execute(INSERT_ENTRY)
        execute("DELETE FROM sanctions_change_journal")
        execute("UPDATE sanctions_entries SET primary_name = 'Real content change'")
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.WITHHELD)
        assertThat(journalCount()).isEqualTo(1)
        assertThat(eventCount("SANCTIONS_LIST_CHANGED")).isZero()
        assertThat(eventCount("SANCTIONS_LIST_CHANGE_STORM")).isEqualTo(1)
        retainPayloads("SANCTIONS_LIST_CHANGE_STORM", payloads("SANCTIONS_LIST_CHANGE_STORM"))
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.WITHHELD)
        assertThat(journalCount()).isEqualTo(1)
        assertThat(eventCount("SANCTIONS_LIST_CHANGE_STORM")).isEqualTo(1)
    }

    @Test
    fun `first population publishes bounded chunks and drains all evidence`() {
        execute(
            """
                INSERT INTO sanctions_entries (list_type, external_id, primary_name)
                SELECT 'PEP_GLOBAL', 'chunk-fixture-' || n, 'Example ' || n FROM generate_series(1, 130) n
            """.trimIndent(),
        )
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.PUBLISHED)
        assertThat(journalCount()).isZero()
        assertThat(eventCount("SANCTIONS_LIST_CHANGED")).isEqualTo(3)
        val included = onEventLoop {
            pool.query(
                "SELECT sum((payload::jsonb ->> 'changeCount')::int) AS total FROM sanctions_outbox " +
                    "WHERE event_type = 'SANCTIONS_LIST_CHANGED'",
            ).execute().awaitSuspending().iterator().next().getLong("total")
        }
        assertThat(included).isEqualTo(130)
        val payloads = payloads("SANCTIONS_LIST_CHANGED")
        assertThat(payloads.map { it["publicationId"].asText() }.distinct()).hasSize(1)
        assertThat(payloads.map { it["chunkIndex"].asInt() }).containsExactly(0, 1, 2)
        assertThat(payloads.map { it["chunkCount"].asInt() }).containsOnly(3)
        val ids = payloads.flatMap { node -> node["changedExternalIds"].map { it.asText() } }
        assertThat(ids).containsExactlyInAnyOrderElementsOf((1..130).map { "chunk-fixture-$it" })
        retainPayloads("SANCTIONS_LIST_CHANGED", payloads)
    }

    private fun jdbc(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private fun awaitPublisherSelection(connection: Connection) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiting = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                        "AND query LIKE '%FOR UPDATE%' AND query LIKE '%sanctions_change_journal%'",
                ).use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
            if (waiting > 0) return
            Thread.sleep(20)
        }
        error("Publisher did not reach its journal selection lock")
    }

    private fun awaitPublisherSelectionOnNewConnection() {
        jdbc().use { awaitPublisherSelection(it) }
    }

    @Test
    fun `concurrent later commits survive even with an earlier journal sequence id`() {
        execute(
            "INSERT INTO sanctions_entries (list_type, external_id, primary_name) " +
                "SELECT 'PEP_GLOBAL', 'baseline-' || n, 'Baseline ' || n FROM generate_series(1, 10) n",
        )
        execute("DELETE FROM sanctions_change_journal")
        execute(INSERT_ENTRY)
        jdbc().use { holder ->
            jdbc().use { hidden ->
                holder.autoCommit = false
                hidden.autoCommit = false
                holder.createStatement().use {
                    it.execute(
                        "SELECT id FROM sanctions_change_journal WHERE external_id = 'journal-fixture' FOR UPDATE",
                    )
                }
                hidden.createStatement().use {
                    it.execute(INSERT_ENTRY.replace("journal-fixture", "hidden-earlier-id"))
                }
                execute(INSERT_ENTRY.replace("journal-fixture", "visible-later-id"))
                val publishing = CompletableFuture.supplyAsync { publish() }
                try {
                    awaitPublisherSelectionOnNewConnection()
                    hidden.commit()
                    execute(
                        "UPDATE sanctions_entries SET primary_name = 'Concurrent change' WHERE external_id = 'journal-fixture'",
                    )
                } finally {
                    holder.commit()
                }
                assertThat(publishing.get(15, TimeUnit.SECONDS)).isEqualTo(SanctionsPublicationOutcome.PUBLISHED)
            }
        }
        val remaining = onEventLoop {
            pool.query("SELECT external_id FROM sanctions_change_journal ORDER BY id").execute()
                .awaitSuspending().map { it.getString("external_id") }
        }
        assertThat(remaining).containsExactly("hidden-earlier-id", "journal-fixture")
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.PUBLISHED)
        assertThat(journalCount()).isZero()
    }

    private fun writeOrdinaryEvents(count: Int) {
        onEventLoop {
            repeat(count) {
                Panache.withTransaction {
                    outbox.persistInTransaction(
                        OutboxMessage(
                            aggregateId = UUID.randomUUID(),
                            eventType = "JOURNAL_ALLOCATOR_TEST",
                            payload = "{}",
                        ),
                    )
                }.awaitSuspending()
            }
        }
    }

    @Test
    fun `publisher and ordinary outbox writes share Hibernate allocation blocks`() {
        writeOrdinaryEvents(60)
        execute(INSERT_ENTRY)
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.PUBLISHED)
        writeOrdinaryEvents(60)
        execute("DELETE FROM sanctions_entries")
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.WITHHELD)
        val counts = onEventLoop {
            pool.query("SELECT count(*) AS total, count(DISTINCT id) AS distinct_ids FROM sanctions_outbox")
                .execute().awaitSuspending().iterator().next()
        }
        assertThat(counts.getLong("total")).isEqualTo(122)
        assertThat(counts.getLong("distinct_ids")).isEqualTo(122)
        val claimed = onEventLoop { outbox.claimProcessable(200, java.time.Duration.ofMinutes(5)) }
        assertThat(claimed).hasSize(122)
    }

    private fun payloads(type: String): List<JsonNode> = onEventLoop {
        pool.preparedQuery("SELECT payload, aggregate_id FROM sanctions_outbox WHERE event_type = $1 ORDER BY id")
            .execute(io.vertx.mutiny.sqlclient.Tuple.of(type)).awaitSuspending()
            .map { row ->
                mapper.readTree(row.getString("payload")).also { payload ->
                    assertThat(payload["aggregateId"].asText()).isEqualTo(row.getUUID("aggregate_id").toString())
                    assertThat(payload["aggregateType"].asText()).isEqualTo("SanctionsList")
                }
            }
    }

    private fun retainPayloads(type: String, payloads: List<JsonNode>) {
        val directory = Path.of("build", "test-results", "sanctions-events")
        Files.createDirectories(directory)
        mapper.writeValue(directory.resolve("$type.json").toFile(), payloads)
    }

    @Test
    fun `coalesced transitions place each source key in exactly one target array`() {
        execute(
            "INSERT INTO sanctions_entries (list_type, external_id, primary_name) " +
                "SELECT 'PEP_GLOBAL', 'source-' || n, 'Baseline ' || n FROM generate_series(1, 10) n",
        )
        execute("DELETE FROM sanctions_change_journal")
        execute("UPDATE sanctions_entries SET active = false WHERE external_id = 'source-1'")
        execute("UPDATE sanctions_entries SET active = true WHERE external_id = 'source-1'")
        execute("UPDATE sanctions_entries SET active = false WHERE external_id = 'source-1'")
        execute("UPDATE sanctions_entries SET primary_name = 'Changed' WHERE external_id = 'source-2'")
        execute("UPDATE sanctions_entries SET external_id = 'renamed-source' WHERE external_id = 'source-3'")
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.PUBLISHED)
        val payload = payloads("SANCTIONS_LIST_CHANGED").single()
        assertThat(payload["changedExternalIds"].map { it.asText() })
            .containsExactlyInAnyOrder("source-2", "renamed-source")
        assertThat(payload["deactivatedExternalIds"].map { it.asText() })
            .containsExactlyInAnyOrder("source-1", "source-3")
        assertThat(payload["changeCount"].asInt()).isEqualTo(4)
        assertThat(journalCount()).isZero()
        retainPayloads("SANCTIONS_LIST_CHANGED_MIXED", listOf(payload))
    }

    @Test
    fun `missing source identity retains evidence instead of silently dropping a target`() {
        execute("INSERT INTO sanctions_entries (list_type, primary_name) VALUES ('PEP_GLOBAL', 'No source key')")
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.WITHHELD)
        assertThat(journalCount()).isEqualTo(1)
        assertThat(payloads("SANCTIONS_LIST_CHANGE_STORM").single()["reason"].asText()).isEqualTo("MISSING_SOURCE_ID")
    }

    @Test
    fun `repairing a missing source identity releases retained publication`() {
        execute(
            "INSERT INTO sanctions_entries (list_type, external_id, primary_name) " +
                "SELECT 'PEP_GLOBAL', 'baseline-' || n, 'Baseline ' || n FROM generate_series(1, 10) n",
        )
        execute("DELETE FROM sanctions_change_journal")
        execute("INSERT INTO sanctions_entries (list_type, primary_name) VALUES ('PEP_GLOBAL', 'No source key')")
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.WITHHELD)

        execute("UPDATE sanctions_entries SET external_id = 'repaired-source' WHERE primary_name = 'No source key'")
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.PUBLISHED)
        assertThat(unresolvedJournalCount()).isZero()
        assertThat(journalCount()).isEqualTo(2)
        assertThat(payloads("SANCTIONS_LIST_CHANGED").single()["changedExternalIds"].map { it.asText() })
            .containsExactly("repaired-source")
    }

    @Test
    fun `failed repaired publication keeps unresolved evidence for retry`() {
        execute(
            "INSERT INTO sanctions_entries (list_type, external_id, primary_name) " +
                "SELECT 'PEP_GLOBAL', 'baseline-' || n, 'Baseline ' || n FROM generate_series(1, 10) n",
        )
        execute("DELETE FROM sanctions_change_journal")
        execute("INSERT INTO sanctions_entries (list_type, primary_name) VALUES ('PEP_GLOBAL', 'No source key')")
        execute("UPDATE sanctions_entries SET external_id = 'repaired-source' WHERE primary_name = 'No source key'")
        execute(
            "ALTER TABLE sanctions_outbox ADD CONSTRAINT reject_repair_for_test " +
                "CHECK (event_type <> 'SANCTIONS_LIST_CHANGED') NOT VALID",
        )
        try {
            assertThatThrownBy { publish() }.hasMessageContaining("reject_repair_for_test")
            assertThat(unresolvedJournalCount()).isEqualTo(3)
        } finally {
            execute("ALTER TABLE sanctions_outbox DROP CONSTRAINT reject_repair_for_test")
        }
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.PUBLISHED)
        assertThat(unresolvedJournalCount()).isZero()
    }

    @Test
    fun `repair into another list releases the old invalid journal`() {
        execute(
            "INSERT INTO sanctions_entries (list_type, external_id, primary_name) " +
                "SELECT 'PEP_GLOBAL', 'pep-baseline-' || n, 'PEP Baseline ' || n FROM generate_series(1, 10) n",
        )
        execute(
            "INSERT INTO sanctions_entries (list_type, external_id, primary_name) " +
                "SELECT 'OFAC_SDN', 'ofac-baseline-' || n, 'OFAC Baseline ' || n FROM generate_series(1, 10) n",
        )
        execute("DELETE FROM sanctions_change_journal")
        execute("INSERT INTO sanctions_entries (list_type, primary_name) VALUES ('PEP_GLOBAL', 'Moved source')")
        execute(
            "UPDATE sanctions_entries SET list_type = 'OFAC_SDN', external_id = 'moved-source' " +
                "WHERE primary_name = 'Moved source'",
        )

        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.NO_CHANGES)
        assertThat(unresolvedJournalCount()).isEqualTo(1)
        val archivedTargets = onEventLoop {
            pool.query(
                "SELECT resolved_by_list_type FROM sanctions_change_journal " +
                    "WHERE resolved_at IS NOT NULL ORDER BY id",
            )
                .execute().awaitSuspending().map { it.getString("resolved_by_list_type") }
        }
        assertThat(archivedTargets).containsExactly("OFAC_SDN", "OFAC_SDN")
        assertThat(publish(SanctionsListType.OFAC_SDN)).isEqualTo(SanctionsPublicationOutcome.PUBLISHED)
        assertThat(unresolvedJournalCount()).isZero()
        assertThat(payloads("SANCTIONS_LIST_CHANGED").single()["changedExternalIds"].map { it.asText() })
            .containsExactly("moved-source")
    }

    @Test
    fun `oversized encoded source identity retains evidence instead of creating an oversized event`() {
        execute(
            "INSERT INTO sanctions_entries (list_type, external_id, primary_name) " +
                "VALUES ('PEP_GLOBAL', repeat('é', 2050), 'Long source key')",
        )
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.WITHHELD)
        assertThat(journalCount()).isEqualTo(1)
        assertThat(payloads("SANCTIONS_LIST_CHANGE_STORM").single()["reason"].asText()).isEqualTo("SOURCE_ID_TOO_LARGE")
    }

    @Test
    fun `large retained storm is summarized without materializing its entries in the publisher`() {
        val rowCount = System.getenv("SANCTIONS_JOURNAL_STRESS_ROWS")?.toInt() ?: DEFAULT_STRESS_ROWS
        require(rowCount in 1..MAX_STRESS_ROWS)
        System.getenv("SANCTIONS_JOURNAL_MAX_HEAP_BYTES")?.toLong()?.let { limit ->
            assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(limit)
        }
        execute(
            "INSERT INTO sanctions_entries (list_type, external_id, primary_name) " +
                "SELECT 'PEP_GLOBAL', 'stress-' || n, 'Baseline ' || n FROM generate_series(1, $rowCount) n",
        )
        execute("DELETE FROM sanctions_change_journal")
        execute("UPDATE sanctions_entries SET primary_name = primary_name || ' changed'")
        val started = System.nanoTime()
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.WITHHELD)
        assertThat(journalCount()).isEqualTo(rowCount.toLong())
        assertThat(eventCount("SANCTIONS_LIST_CHANGED")).isZero()
        assertThat(eventCount("SANCTIONS_LIST_CHANGE_STORM")).isEqualTo(1)
        assertThat(publish()).isEqualTo(SanctionsPublicationOutcome.WITHHELD)
        assertThat(journalCount()).isEqualTo(rowCount.toLong())
        assertThat(eventCount("SANCTIONS_LIST_CHANGE_STORM")).isEqualTo(1)
        println(
            "Journal stress proof: rows=$rowCount, twoPublicationsMillis=${(System.nanoTime() - started) / 1_000_000}",
        )
    }

    private companion object {
        const val DEFAULT_STRESS_ROWS = 20_000
        const val MAX_STRESS_ROWS = 1_000_000
        const val INSERT_ENTRY = """
            INSERT INTO sanctions_entries (list_type, external_id, primary_name, aliases_json, search_text)
            VALUES ('PEP_GLOBAL', 'journal-fixture', 'Example Person', '["Alias A", "Alias B"]',
                    'example person | alias a | alias b')
        """
    }
}
