// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.persistence

import com.openbank.risk.application.port.out.CurveSetRepository
import com.openbank.risk.application.port.out.CurveSetSummary
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurvePillar
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.curve.MoneyMarketQuote
import com.openbank.risk.domain.model.Provenance
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.SqlConnection
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.ZoneOffset
import java.util.UUID

/** Curve sets on the plain reactive SQL client; the set, its quotes and pillars commit together. */
@ApplicationScoped
class PgCurveSetRepository(private val pool: Pool) : CurveSetRepository {

    override suspend fun save(set: CurveSet, quotes: Map<CurveIndex, List<MoneyMarketQuote>>) {
        val quoteRows = quotes.flatMap { (index, qs) ->
            qs.map { Tuple.tuple(listOf(set.id, index.name, it.tenor.code, it.simpleRate)) }
        }
        val pillarRows = set.curves.flatMap { (index, curve) ->
            curve.pillars.map { Tuple.tuple(listOf(set.id, index.name, it.date, it.zeroRate)) }
        }
        pool.withTransaction { conn ->
            conn.preparedQuery(INSERT_SET).execute(
                Tuple.tuple(
                    listOf(set.id, set.asOf, set.provenance.wire, set.source, set.recordedAt.atOffset(ZoneOffset.UTC)),
                ),
            )
                .flatMap { batch(conn, INSERT_QUOTE, quoteRows) }
                .flatMap { batch(conn, INSERT_PILLAR, pillarRows) }
        }.awaitSuspending()
    }

    private fun batch(conn: SqlConnection, sql: String, rows: List<Tuple>): Uni<Void> = if (rows.isEmpty()) {
        Uni.createFrom().voidItem()
    } else {
        conn.preparedQuery(
            sql,
        ).executeBatch(rows).replaceWithVoid()
    }

    override suspend fun listRecent(limit: Int): List<CurveSetSummary> =
        pool.preparedQuery(SELECT_RECENT).execute(Tuple.of(limit)).awaitSuspending().map { row ->
            CurveSetSummary(
                id = row.getUUID("id"),
                asOf = row.getLocalDate("as_of"),
                provenance = Provenance.parse(row.getString("provenance")).wire,
                source = row.getString("source"),
                recordedAt = row.getOffsetDateTime("recorded_at").toInstant(),
                indices = row.getString("indices")?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            )
        }

    override suspend fun findById(id: UUID): CurveSet? {
        val row = pool.preparedQuery(SELECT_SET).execute(Tuple.of(id)).awaitSuspending().firstOrNull() ?: return null
        val asOf = row.getLocalDate("as_of")
        val pillars = pool.preparedQuery(SELECT_PILLARS).execute(Tuple.of(id)).awaitSuspending().map {
            CurveIndex.valueOf(it.getString("curve_index")) to
                CurvePillar(it.getLocalDate("pillar_date"), it.getBigDecimal("zero_rate"))
        }
        val curves = pillars.groupBy({ it.first }, { it.second })
            .mapValues { (index, ps) -> Curve(index, asOf, ps) }
        return CurveSet(
            id = id,
            asOf = asOf,
            provenance = Provenance.parse(row.getString("provenance")),
            source = row.getString("source"),
            recordedAt = row.getOffsetDateTime("recorded_at").toInstant(),
            curves = curves,
        )
    }

    private companion object {
        const val INSERT_SET =
            "INSERT INTO curve_set (id, as_of, provenance, source, recorded_at) VALUES ($1, $2, $3, $4, $5)"
        const val INSERT_QUOTE =
            "INSERT INTO curve_set_quote (curve_set_id, curve_index, tenor, simple_rate) VALUES ($1, $2, $3, $4)"
        const val INSERT_PILLAR =
            "INSERT INTO curve_set_pillar (curve_set_id, curve_index, pillar_date, zero_rate) VALUES ($1, $2, $3, $4)"
        const val SELECT_RECENT =
            "SELECT s.id, s.as_of, s.provenance, s.source, s.recorded_at, " +
                "(SELECT string_agg(DISTINCT p.curve_index, ',' ORDER BY p.curve_index) FROM curve_set_pillar p " +
                "WHERE p.curve_set_id = s.id) AS indices FROM curve_set s ORDER BY s.recorded_at DESC, s.id LIMIT $1"
        const val SELECT_SET = "SELECT id, as_of, provenance, source, recorded_at FROM curve_set WHERE id = $1"
        const val SELECT_PILLARS =
            "SELECT curve_index, pillar_date, zero_rate FROM curve_set_pillar WHERE curve_set_id = $1 " +
                "ORDER BY curve_index, pillar_date"
    }
}
