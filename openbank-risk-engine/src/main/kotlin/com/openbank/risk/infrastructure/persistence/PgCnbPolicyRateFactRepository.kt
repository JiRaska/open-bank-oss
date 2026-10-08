// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.persistence

import com.openbank.risk.application.port.out.CnbPolicyRateFactRepository
import com.openbank.risk.application.port.out.CnbPolicyRateFactRow
import com.openbank.risk.application.port.out.FactUpsert
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `cnb_policy_rate_fact`, upserted on (instrument, effective_from). The update fires only when the
 * rate actually differs, so a redelivery touches no row; `xmax = 0` on the returned row tells an
 * insert from an update.
 */
@ApplicationScoped
class PgCnbPolicyRateFactRepository(private val pool: Pool) : CnbPolicyRateFactRepository {

    override suspend fun upsert(fact: CnbPolicyRateFactRow): FactUpsert {
        val rows = pool.preparedQuery(UPSERT).execute(
            Tuple.tuple(
                listOf(
                    fact.instrument,
                    fact.effectiveFrom,
                    fact.rate,
                    fact.sourceUrl,
                    fact.fetchedAt.atOffset(ZoneOffset.UTC),
                    fact.contentSha256,
                    fact.note,
                    fact.revised,
                    fact.receivedAt.atOffset(ZoneOffset.UTC),
                ),
            ),
        ).awaitSuspending()
        val row = rows.firstOrNull() ?: return FactUpsert.DUPLICATE
        return if (row.getBoolean("inserted")) FactUpsert.INSERTED else FactUpsert.REVISED
    }

    override suspend fun effectiveAt(instrument: String, asOf: LocalDate): CnbPolicyRateFactRow? =
        pool.preparedQuery(EFFECTIVE).execute(Tuple.of(instrument, asOf)).awaitSuspending().firstOrNull()?.toFact()

    private fun Row.toFact() = CnbPolicyRateFactRow(
        instrument = getString("instrument"),
        effectiveFrom = getLocalDate("effective_from"),
        rate = getBigDecimal("rate"),
        sourceUrl = getString("source_url"),
        fetchedAt = getOffsetDateTime("fetched_at").toInstant(),
        contentSha256 = getString("content_sha256"),
        note = getString("note"),
        revised = getBoolean("revised"),
        receivedAt = getOffsetDateTime("received_at").toInstant(),
    )

    private companion object {
        const val UPSERT =
            "INSERT INTO cnb_policy_rate_fact (instrument, effective_from, rate, source_url, fetched_at, " +
                "content_sha256, note, revised, received_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) " +
                "ON CONFLICT (instrument, effective_from) DO UPDATE SET rate = EXCLUDED.rate, " +
                "source_url = EXCLUDED.source_url, fetched_at = EXCLUDED.fetched_at, " +
                "content_sha256 = EXCLUDED.content_sha256, note = EXCLUDED.note, revised = TRUE, " +
                "received_at = EXCLUDED.received_at WHERE cnb_policy_rate_fact.rate <> EXCLUDED.rate " +
                "RETURNING (xmax = 0) AS inserted"

        const val EFFECTIVE =
            "SELECT instrument, effective_from, rate, source_url, fetched_at, content_sha256, note, revised, " +
                "received_at FROM cnb_policy_rate_fact WHERE instrument = $1 AND effective_from <= $2 " +
                "ORDER BY effective_from DESC LIMIT 1"
    }
}
