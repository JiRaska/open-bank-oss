// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AuditConcurrentAppendIT {
    @Test
    @Suppress("NestedBlockDepth") // Deliberately bracket the database barrier and resource cleanup.
    fun `independent writers serialize the chain head with the append transaction`() {
        val marker = "chainprobe_${UUID.randomUUID().toString().replace("-", "") }"
        val trigger = "${marker}_trigger"
        val function = "${marker}_barrier"
        val first = entry(marker)
        val second = entry(marker)
        val writes = mutableListOf<CompletableFuture<Void>>()
        jdbc().use { barrier ->
            barrier.createStatement().use { it.execute("SELECT pg_advisory_lock($BARRIER_LOCK)") }
            sql(
                "CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS " +
                    "'BEGIN PERFORM pg_advisory_xact_lock($BARRIER_LOCK); RETURN NEW; END'",
            )
            sql(
                "CREATE TRIGGER $trigger BEFORE INSERT ON audit_entries FOR EACH ROW " +
                    "WHEN (NEW.aggregate_id = '$marker') EXECUTE FUNCTION $function()",
            )
            try {
                // Independent repository instances model separate service replicas, each with its own mutex.
                writes += append(AuditRepository(), first)
                awaitBlocked(1)
                writes += append(AuditRepository(), second)
                // Old code reaches the INSERT barrier twice with the same head. Fixed code instead
                // blocks the second writer before it can read the head. Both cases have two waiters.
                awaitBlocked(2)
            } finally {
                barrier.createStatement().use { it.execute("SELECT pg_advisory_unlock($BARRIER_LOCK)") }
                try {
                    writes.forEach { it.get(20, TimeUnit.SECONDS) }
                } finally {
                    sql("DROP TRIGGER $trigger ON audit_entries")
                    sql("DROP FUNCTION $function()")
                }
            }
        }
        val links = jdbc().use { connection ->
            connection.prepareStatement(
                "SELECT prev_hash, record_hash FROM audit_entries WHERE aggregate_id = ? ORDER BY id",
            ).use { query ->
                query.setString(1, marker)
                query.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
                }
            }
        }
        assertThat(links).hasSize(2)
        assertThat(links[1].first).isEqualTo(links[0].second)
    }

    @Test
    fun `another allocator cannot put a later append behind the chain head`() {
        val repository = AuditRepository()
        val marker = UUID.randomUUID().toString()
        val first = entry(marker)
        val middle = entry(marker)
        val last = entry(marker)
        append(repository, first).get(20, TimeUnit.SECONDS)
        val head = jdbc().use { connection ->
            connection.prepareStatement("SELECT record_hash FROM audit_entries WHERE entry_id = ?").use { query ->
                query.setObject(1, first.id)
                query.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        }
        // A fresh process reserves a higher sequence block while this process still owns lower IDs.
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) {
                repository.appendRawRow(middle, head, AuditRepository.chainHash(head, middle), 2)
            }
        }
        append(repository, last).get(20, TimeUnit.SECONDS)
        val order = jdbc().use { connection ->
            connection.prepareStatement(
                "SELECT entry_id FROM audit_entries WHERE aggregate_id = ? ORDER BY id",
            ).use { query ->
                query.setString(1, marker)
                query.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) }
                }
            }
        }
        assertThat(order).containsExactly(first.id, middle.id, last.id)
    }

    private fun append(repository: AuditRepository, entry: AuditEntry): CompletableFuture<Void> =
        CompletableFuture.runAsync {
            VertxContextSupport.subscribeAndAwait {
                uni(CoroutineScope(Dispatchers.Unconfined)) { repository.save(entry) }
            }
        }

    private fun awaitBlocked(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (blockedAppends() < expected && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(blockedAppends()).isGreaterThanOrEqualTo(expected)
    }

    private fun blockedAppends(): Int = jdbc().use { connection ->
        connection.createStatement().use { query ->
            query.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                    "AND wait_event_type = 'Lock' AND wait_event = 'advisory'",
            ).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun entry(marker: String) = AuditEntry(
        id = UUID.randomUUID(), eventType = "chain.concurrent.test", aggregateType = "ACCOUNT",
        aggregateId = marker, actorId = "test-writer", actorType = "SYSTEM", payload = "{}",
        sourceService = "audit-service", correlationId = marker,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"), recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
        occurredAtSource = com.openbank.audit.domain.model.OccurredAtSource.EVENT,
    )

    private fun jdbc() = ConfigProvider.getConfig().let { config ->
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private fun sql(statement: String) {
        jdbc().use { connection -> connection.createStatement().use { it.execute(statement) } }
    }

    private companion object {
        const val BARRIER_LOCK = 913_004_211L
    }
}
