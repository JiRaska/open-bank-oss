// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsEntryRepositoryImpl
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import javax.sql.DataSource

/**
 * Shared plumbing of the ongoing-monitoring integration tests. Every name, reference and country
 * code here is invented; `XM` is a user-assigned ISO code that no real country holds.
 */
internal class MonitoringFixture(private val dataSource: DataSource, private val entries: SanctionsEntryRepositoryImpl) {

    fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    fun sql(statement: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(statement) } }
    }

    fun <T> query(statement: String, read: (java.sql.ResultSet) -> T): List<T> =
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(statement).use { rows ->
                    generateSequence { if (rows.next()) read(rows) else null }.toList()
                }
            }
        }

    fun count(table: String, where: String = "TRUE"): Long =
        query("SELECT count(*) FROM $table WHERE $where") { it.getLong(1) }.single()

    /** Ingest one entry through the importer's own upsert, so the real triggers fire. */
    fun ingest(externalId: String, name: String, vararg aliases: String, list: SanctionsListType = SanctionsListType.OFAC_SDN) {
        val now = Instant.now()
        val written = onEventLoop {
            entries.upsertAll(
                listOf(
                    SanctionsEntry(
                        listType = list,
                        externalId = externalId,
                        entityType = EntityType.INDIVIDUAL,
                        primaryName = name,
                        aliases = aliases.toList(),
                        searchText = (listOf(name) + aliases).joinToString(" | ") { it.lowercase() },
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            )
        }
        lastIngestWrote = written
    }

    var lastIngestWrote: Int = -1
        private set

    fun cleanUp() {
        sql("DELETE FROM sanctions_monitored_subjects")
        sql("DELETE FROM sanctions_monitoring_pending")
        sql("UPDATE sanctions_monitoring_state SET resweep_requested_at = NULL")
        sql("DELETE FROM sanctions_entries WHERE external_id LIKE '$ENTRY_PREFIX%'")
        sql("DELETE FROM sanctions_change_journal WHERE external_id LIKE '$ENTRY_PREFIX%'")
        sql("DELETE FROM sanctions_outbox WHERE event_type = 'MONITORED_SUBJECT_MATCHED'")
        sql("DELETE FROM sanctions_checks WHERE subject_id LIKE '$SUBJECT_PREFIX%'")
        sql("DELETE FROM sanctions_jurisdictions WHERE source_list = '$SOURCE_LIST'")
    }

    companion object {
        const val ENTRY_PREFIX = "monitoring-it-"
        const val SUBJECT_PREFIX = "monitoring-it-subject-"
        const val SOURCE_LIST = "SYNTHETIC_MONITORING"
        const val OWNER = "kyc-service"
        const val COUNTRY = "XM"
        const val LISTED = "Zorvath Quellinbrook"
        const val OTHER_LISTED = "Brindlemoor Ashquith"
        const val UNLISTED = "Harmless Nobodyson"
    }
}
