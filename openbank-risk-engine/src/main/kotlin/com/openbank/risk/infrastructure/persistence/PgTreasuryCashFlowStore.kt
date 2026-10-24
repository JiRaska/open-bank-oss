// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.risk.application.port.out.TreasuryCashFlowStore
import com.openbank.risk.domain.valuation.StoredFlowSet
import com.openbank.risk.infrastructure.finance.CashFlowDto
import com.openbank.risk.infrastructure.finance.CashFlowJson
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Treasury's stored cash-flow ledger (V11 `treasury_deal_cash_flows`, ADR-0344 D4): one row per
 * deal, replaced only by a strictly NEWER deal version, so redelivery and a late older version are
 * no-ops.
 */
@ApplicationScoped
class PgTreasuryCashFlowStore(private val pool: Pool, private val objectMapper: ObjectMapper) : TreasuryCashFlowStore {

    override suspend fun apply(set: StoredFlowSet): Boolean = pool.preparedQuery(UPSERT).execute(
        Tuple.of(
            set.dealId,
            set.dealVersion,
            set.scheduleHash,
            io.vertx.core.json.JsonArray(objectMapper.writeValueAsString(set.flows.map(CashFlowJson::toDto))),
        ),
    ).awaitSuspending().rowCount() > 0

    override suspend fun latest(dealIds: Collection<UUID>): Map<UUID, StoredFlowSet> {
        if (dealIds.isEmpty()) return emptyMap()
        return pool.preparedQuery(
            SELECT,
        ).execute(Tuple.tuple(listOf<Any>(dealIds.toTypedArray()))).awaitSuspending().associate { row ->
            val id = row.getUUID("deal_id")
            val flows = objectMapper.readValue(row.getString("flows"), object : TypeReference<List<CashFlowDto>>() {})
            id to StoredFlowSet(
                id,
                row.getInteger("deal_version"),
                row.getString("schedule_hash"),
                flows.mapIndexed(CashFlowJson::toDomain),
            )
        }
    }

    private companion object {
        const val UPSERT =
            "INSERT INTO treasury_deal_cash_flows (deal_id, deal_version, schedule_hash, flows) " +
                "VALUES ($1, $2, $3, $4::jsonb) ON CONFLICT (deal_id) DO UPDATE SET " +
                "deal_version = EXCLUDED.deal_version, schedule_hash = EXCLUDED.schedule_hash, " +
                "flows = EXCLUDED.flows, received_at = now() " +
                "WHERE EXCLUDED.deal_version > treasury_deal_cash_flows.deal_version"
        const val SELECT =
            "SELECT deal_id, deal_version, schedule_hash, flows::text AS flows FROM treasury_deal_cash_flows " +
                "WHERE deal_id = ANY($1)"
    }
}
