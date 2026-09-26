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

    private companion object {
        const val INSERT =
            "INSERT INTO fx_fixing_rate (source, fixing_date, currency, quote_currency, rate_per_unit, rate_id, " +
                "valid_from, valid_to, received_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) " +
                "ON CONFLICT (source, fixing_date, currency) DO NOTHING"
    }
}
