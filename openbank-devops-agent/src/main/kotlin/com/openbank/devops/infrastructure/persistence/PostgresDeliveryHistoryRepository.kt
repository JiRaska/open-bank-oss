// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.application.port.out.DeliveryHistoryRepository
import com.openbank.devops.domain.model.DeliveryObservation
import com.openbank.devops.domain.model.DeliveryOverview
import com.openbank.devops.infrastructure.config.DevOpsConfig
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Duration

@ApplicationScoped
class PostgresDeliveryHistoryRepository(
    private val sf: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    private val config: DevOpsConfig,
) : DeliveryHistoryRepository {
    override suspend fun save(observation: DeliveryObservation) {
        val sample = observation.overview
        require(observation.schemaVersion == SCHEMA_VERSION)
        // Bind text before the JSONB cast: a JSONB-typed parameter JSON-encodes a String a second time.
        sf.withTransaction { s ->
            s.createNativeQuery<Int>(
                """INSERT INTO delivery_observations
                    (id, repository, slot_start, observed_at, recorded_at, collector_version, schema_version, snapshot_json)
                    VALUES (:id, :repo, :slot, :observed, :recorded, :version, :schema, CAST(CAST(:payload AS text) AS jsonb))
                    ON CONFLICT (repository, slot_start) DO NOTHING
                """.trimIndent(),
            ).setParameter("id", Ids.newId())
                .setParameter("repo", sample.repository)
                .setParameter("slot", observation.slotStart)
                .setParameter("observed", sample.observedAt)
                .setParameter("recorded", observation.recordedAt)
                .setParameter("version", observation.collectorVersion)
                .setParameter("schema", observation.schemaVersion)
                .setParameter("payload", mapper.writeValueAsString(sample))
                .executeUpdate().flatMap {
                    s.createNativeQuery<Int>(
                        """DELETE FROM delivery_observations WHERE id IN
                            (SELECT id FROM delivery_observations WHERE repository = :repo AND observed_at < :cutoff
                             ORDER BY observed_at LIMIT 1000)
                        """.trimIndent(),
                    ).setParameter("repo", sample.repository)
                        .setParameter("cutoff", observation.recordedAt.minus(RETENTION))
                        .executeUpdate()
                }
        }.subscribeAsCompletionStage().await()
    }

    override suspend fun findRecent(limit: Int): List<DeliveryObservation> {
        require(limit in 1..MAX_HISTORY)
        val repository = "${config.githubOwner()}/${config.githubRepo()}"
        return sf.withSession { s ->
            s.createQuery(
                "FROM DeliveryObservationEntity WHERE repository = :repo AND schemaVersion = :schema " +
                    "ORDER BY observedAt DESC, id DESC",
                DeliveryObservationEntity::class.java,
            ).setParameter("repo", repository).setParameter("schema", SCHEMA_VERSION)
                .setMaxResults(limit).resultList
        }.map { rows ->
            rows.map { row ->
                DeliveryObservation(
                    row.slotStart,
                    row.recordedAt,
                    row.collectorVersion,
                    mapper.readValue(row.snapshotJson, DeliveryOverview::class.java),
                    row.schemaVersion,
                )
            }
        }.subscribeAsCompletionStage().await()
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_HISTORY = 288
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
