// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.persistence

import com.openbank.risk.application.port.out.FxFixingRate
import com.openbank.risk.application.port.out.FxFixingRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset

/**
 * Idempotent on the primary key (source, fixing_date, currency): a redelivered or replayed
 * fixing event inserts nothing. First writer wins — a fixing, once published, is a fact about
 * that day, and fx-service's own ingestion is idempotent on the same key.
 */
@ApplicationScoped
class PgFxFixingRepository(private val pool: Pool) : FxFixingRepository {

    override suspend fun insertIfAbsent(rates: List<FxFixingRate>): Int {
        if (rates.isEmpty()) return 0
        return pool.withTransaction { conn ->
            io.smallrye.mutiny.Multi.createFrom().iterable(rates)
                .onItem().transformToUniAndConcatenate { r ->
                    conn.preparedQuery(INSERT).execute(
                        Tuple.tuple(
                            listOf(
                                r.source,
                                r.fixingDate,
                                r.currency,
                                r.quoteCurrency,
                                r.ratePerUnit,
                                r.rateId,
                                r.validFrom.atOffset(ZoneOffset.UTC),
                                r.validTo.atOffset(ZoneOffset.UTC),
                                r.receivedAt.atOffset(ZoneOffset.UTC),
                            ),
                        ),
                    ).map { it.rowCount() }
                }
                .collect().asList()
                .map { it.sum() }
        }.awaitSuspending()
    }

    override suspend fun inEffect(source: String, currency: String, quoteCurrency: String, at: Instant): FxFixingRate? {
        val atUtc = at.atOffset(ZoneOffset.UTC)
        val rows = pool.preparedQuery(IN_EFFECT)
            .execute(Tuple.of(source, currency, quoteCurrency, atUtc))
            .awaitSuspending()
        return rows.firstOrNull()?.let { r ->
            FxFixingRate(
                source = r.getString("source"),
                fixingDate = r.getLocalDate("fixing_date"),
                currency = r.getString("currency"),
                quoteCurrency = r.getString("quote_currency"),
                ratePerUnit = r.getBigDecimal("rate_per_unit"),
                rateId = r.getUUID("rate_id"),
                validFrom = r.getOffsetDateTime("valid_from").toInstant(),
                validTo = r.getOffsetDateTime("valid_to").toInstant(),
                receivedAt = r.getOffsetDateTime("received_at").toInstant(),
            )
        }
    }

    private companion object {
        const val IN_EFFECT =
            "SELECT source, fixing_date, currency, quote_currency, rate_per_unit, rate_id, valid_from, valid_to, " +
                "received_at FROM fx_fixing_rate WHERE source = $1 AND currency = $2 AND quote_currency = $3 " +
                "AND valid_from <= $4 AND valid_to > $4 ORDER BY valid_from DESC, received_at DESC LIMIT 1"

        const val INSERT =
            "INSERT INTO fx_fixing_rate (source, fixing_date, currency, quote_currency, rate_per_unit, rate_id, " +
                "valid_from, valid_to, received_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) " +
                "ON CONFLICT (source, fixing_date, currency) DO NOTHING"
    }
}
