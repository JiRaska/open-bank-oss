// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.persistence

import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.application.port.out.TreasuryDealEvent
import com.openbank.risk.domain.model.TreasuryDeal
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate

/**
 * The engine's read model of treasury deals (V4 `treasury_deal`, ADR-0315 D6). One upsert per
 * event whose update fires only when the event's state ranks ABOVE the stored one (or fills a rate
 * the row lacked), so the book is monotonic and a redelivery or a late, older event is a no-op.
 */
@ApplicationScoped
class PgTreasuryDealBook(private val pool: Pool) : TreasuryDealBook {

    override suspend fun dealsOnBook(asOf: LocalDate): List<TreasuryDeal> =
        pool.preparedQuery(SELECT).execute(Tuple.of(asOf)).awaitSuspending()
            .mapNotNull { it.toDeal() }
            .filter { it.onBookAt(asOf) }

    override suspend fun apply(event: TreasuryDealEvent): Boolean = pool.preparedQuery(UPSERT).execute(
        Tuple.tuple(
            listOf(
                event.dealId,
                event.product,
                event.counterpartyId,
                event.currency,
                event.principal,
                event.rate,
                event.valueDate,
                event.maturityDate,
                event.state,
            ),
        ),
    ).awaitSuspending().rowCount() > 0

    private fun Row.toDeal(): TreasuryDeal? {
        val value = getLocalDate("value_date") ?: return null
        val maturity = getLocalDate("maturity_date") ?: return null
        return TreasuryDeal(
            dealId = getUUID("deal_id"),
            product = getString("product"),
            counterpartyId = getString("counterparty_id"),
            currency = getString("currency"),
            principal = getBigDecimal("principal"),
            rate = getBigDecimal("rate"),
            valueDate = value,
            maturityDate = maturity,
            state = getString("state"),
        )
    }

    private companion object {
        // Only rows that can be on the book at asOf: settled ones, and matured ones not yet past it.
        const val SELECT =
            "SELECT deal_id, product, counterparty_id, currency, principal, rate, value_date, maturity_date, " +
                "state FROM treasury_deal WHERE state IN ('SETTLED', 'MATURED') AND value_date <= $1"

        const val RANK = "CASE %s WHEN 'BOOKED' THEN 1 WHEN 'SETTLED' THEN 2 ELSE 3 END"

        val UPSERT =
            "INSERT INTO treasury_deal (deal_id, product, counterparty_id, currency, principal, rate, value_date, " +
                "maturity_date, state) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) " +
                "ON CONFLICT (deal_id) DO UPDATE SET " +
                "state = CASE WHEN ${RANK.format("EXCLUDED.state")} > ${RANK.format("treasury_deal.state")} " +
                "THEN EXCLUDED.state ELSE treasury_deal.state END, " +
                "rate = COALESCE(treasury_deal.rate, EXCLUDED.rate), " +
                "value_date = COALESCE(treasury_deal.value_date, EXCLUDED.value_date), " +
                "maturity_date = COALESCE(treasury_deal.maturity_date, EXCLUDED.maturity_date), " +
                "updated_at = now() " +
                "WHERE ${RANK.format("EXCLUDED.state")} > ${RANK.format("treasury_deal.state")} " +
                "OR (treasury_deal.rate IS NULL AND EXCLUDED.rate IS NOT NULL) " +
                "OR (treasury_deal.value_date IS NULL AND EXCLUDED.value_date IS NOT NULL)"
    }
}
