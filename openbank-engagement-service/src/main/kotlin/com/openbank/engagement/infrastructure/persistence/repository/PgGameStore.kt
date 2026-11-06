// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.GameStore
import com.openbank.engagement.domain.model.game.CustomerGameCatalog
import com.openbank.engagement.domain.model.game.GameAccess
import com.openbank.engagement.domain.model.game.GameCatalog
import com.openbank.engagement.domain.model.game.GameCatalogConflict
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.SqlConnection
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@ApplicationScoped
class PgGameStore(private val pool: PgPool, private val mapper: ObjectMapper) : GameStore {
    override suspend fun catalog(): GameCatalog = pool.query(READ).execute().map {
        decode(it.first().getString("document"))
    }.awaitSuspending()

    override suspend fun update(catalog: GameCatalog, actor: String, at: Instant): GameCatalog {
        require(actor.isNotBlank())
        return pool.withTransaction { connection ->
            connection.query("$READ FOR UPDATE").execute().flatMap { rows ->
                val before = decode(rows.first().getString("document"))
                if (before.revision != catalog.revision) {
                    Uni.createFrom().failure(GameCatalogConflict())
                } else {
                    val after = catalog.copy(revision = before.revision + 1)
                    val json = mapper.writeValueAsString(after)
                    connection.preparedQuery("UPDATE game_catalog SET document = $1 WHERE id = 'lion-quest'")
                        .execute(Tuple.of(json)).flatMap {
                            connection.preparedQuery(
                                "INSERT INTO game_catalog_audit(revision, actor, changed_at, before_document, after_document) VALUES ($1,$2,$3,$4,$5)",
                            ).execute(
                                Tuple.of(
                                    after.revision,
                                    actor,
                                    at.atOffset(ZoneOffset.UTC),
                                    mapper.writeValueAsString(before),
                                    json,
                                ),
                            )
                        }.map { after }
                }
            }
        }.awaitSuspending()
    }

    override suspend fun customer(party: UUID, beta: Boolean, at: Instant): CustomerGameCatalog =
        pool.withTransaction { connection ->
            view(connection, party, beta, at)
        }.awaitSuspending()

    override suspend fun complete(party: UUID, level: String, beta: Boolean, at: Instant): Boolean =
        pool.withTransaction { connection ->
            // Shares the catalogue lock with readers; an OFF/window change cannot race admission.
            view(connection, party, beta, at).flatMap { view ->
                if (view.levels.none { it.level.id == level && it.unlocked }) {
                    Uni.createFrom().item(false)
                } else {
                    connection.preparedQuery(
                        "INSERT INTO game_completion(party_id,level_id,completed_at) VALUES ($1,$2,$3) ON CONFLICT (party_id,level_id) DO NOTHING",
                    ).execute(Tuple.of(party, level, at.atOffset(ZoneOffset.UTC))).map { true }
                }
            }
        }.awaitSuspending()

    private fun view(connection: SqlConnection, party: UUID, beta: Boolean, at: Instant): Uni<CustomerGameCatalog> =
        connection.query("$READ FOR SHARE").execute().flatMap { rows ->
            val catalog = decode(rows.first().getString("document"))
            connection.preparedQuery(
                "SELECT level_id FROM game_completion WHERE party_id = $1",
            ).execute(Tuple.of(party)).flatMap { done ->
                val completed = done.map { it.getString("level_id") }.toSet()
                connection.preparedQuery(
                    "SELECT earn_source_id, count(*) AS total FROM gamification_award WHERE party_id = $1 GROUP BY earn_source_id",
                ).execute(Tuple.of(party)).map { awards ->
                    GameAccess.evaluate(
                        catalog,
                        completed,
                        awards.associate {
                            it.getString("earn_source_id") to
                                it.getLong("total")
                        },
                        beta,
                        at,
                    )
                }
            }
        }

    override suspend fun audit(): List<Map<String, Any>> = pool.query(
        "SELECT revision, actor, changed_at, before_document, after_document FROM game_catalog_audit ORDER BY revision DESC LIMIT 50",
    ).execute().map { rows ->
        rows.map { row ->
            mapOf(
                "revision" to row.getLong("revision"),
                "actor" to row.getString("actor"),
                "changedAt" to row.getOffsetDateTime("changed_at").toInstant().toString(),
                "before" to decode(row.getString("before_document")),
                "after" to decode(row.getString("after_document")),
            )
        }
    }.awaitSuspending()

    override suspend fun erase(party: UUID) {
        pool.preparedQuery("DELETE FROM game_completion WHERE party_id = $1").execute(Tuple.of(party)).awaitSuspending()
    }

    private fun decode(json: String) = mapper.readValue(json, GameCatalog::class.java)

    private companion object {
        const val READ = "SELECT document FROM game_catalog WHERE id = 'lion-quest'"
    }
}
