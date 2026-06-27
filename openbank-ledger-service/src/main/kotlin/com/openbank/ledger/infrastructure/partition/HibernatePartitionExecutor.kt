// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.partition

import com.openbank.libs.persistence.partition.PartitionAuditRecord
import com.openbank.libs.persistence.partition.PartitionExecutor
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

/**
 * Postgres adapter for [PartitionExecutor]. All partition-management SQL lives here so the
 * libs-side [com.openbank.libs.persistence.partition.PartitionMaintenance] orchestrator stays
 * DB-agnostic and unit-testable.
 *
 * Note on injection-safety: partition / table names passed to [executeDdl] and [rowCount] are
 * never user input — they are derived solely from the controlled `journal_entries` prefix and an
 * integer year by [com.openbank.libs.persistence.partition.PartitionManager], so string
 * interpolation here is safe (identifiers cannot be bind parameters in DDL anyway).
 */
@ApplicationScoped
class HibernatePartitionExecutor(private val clock: Clock) : PartitionExecutor {

    override suspend fun listChildPartitions(parentTable: String): List<String> {
        val rows: List<*> = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery(LIST_CHILDREN_SQL, String::class.java)
                    .setParameter("parent", parentTable)
                    .resultList
            }
        }.awaitSuspending()
        return rows.map { it.toString() }
    }

    override suspend fun rowCount(table: String): Long {
        val value = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery("select count(*) from $table", java.lang.Long::class.java)
                    .singleResult
            }
        }.awaitSuspending()
        return (value as Number).toLong()
    }

    override suspend fun executeDdl(ddl: String) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(ddl).executeUpdate()
            }
        }.awaitSuspending()
    }

    override suspend fun recordAudit(record: PartitionAuditRecord) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(INSERT_AUDIT_SQL)
                    .setParameter("parent", record.parentTable)
                    .setParameter("name", record.partitionName)
                    .setParameter("action", record.action.name)
                    .setParameter("reason", record.reason)
                    .setParameter("dryRun", record.dryRun)
                    .setParameter("executedAt", Instant.now(clock))
                    .executeUpdate()
            }
        }.awaitSuspending()
    }

    companion object {
        private val LIST_CHILDREN_SQL = """
            select c.relname
            from pg_inherits i
            join pg_class c on c.oid = i.inhrelid
            join pg_class p on p.oid = i.inhparent
            where p.relname = :parent
        """.trimIndent()

        private val INSERT_AUDIT_SQL = """
            insert into partition_lifecycle_audit
                (parent_table, partition_name, action, reason, dry_run, executed_at)
            values (:parent, :name, :action, :reason, :dryRun, :executedAt)
        """.trimIndent()
    }
}
