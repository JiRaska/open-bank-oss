// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.application.port.out.TreasuryDealEvent
import com.openbank.risk.domain.model.TreasuryDeal
import com.openbank.risk.domain.model.TreasuryInstrumentMapper
import com.openbank.risk.domain.valuation.DealKind
import com.openbank.risk.infrastructure.finance.LegBounds
import com.openbank.risk.infrastructure.finance.LegDefaults
import com.openbank.risk.infrastructure.finance.LegDto
import com.openbank.risk.infrastructure.finance.LegSpecJson
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
class PgTreasuryDealBook(private val pool: Pool, private val objectMapper: ObjectMapper) : TreasuryDealBook {

    override suspend fun dealsOnBook(asOf: LocalDate): List<TreasuryDeal> =
        pool.preparedQuery(SELECT).execute(Tuple.of(asOf)).awaitSuspending()
            .mapNotNull { it.toDeal() }
            // Defensive: the consumer already skips a product this engine cannot map (never stores
            // it), but a row could still exist from before that guard or an out-of-band insert — the
            // mapper must never be asked to build an instrument for a product it does not know.
            .filter { it.onBookAt(asOf) && it.product in TreasuryInstrumentMapper.SUPPORTED_PRODUCTS }

    override suspend fun valuationCandidates(asOf: LocalDate): List<TreasuryDeal> =
        pool.preparedQuery(SELECT_CANDIDATES).execute(Tuple.of(asOf)).awaitSuspending().mapNotNull { it.toDeal() }

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
                event.kind.name,
                event.bookId,
                event.legsJson?.let { io.vertx.core.json.JsonArray(it) },
                event.scheduleHash,
                event.legsError,
            ),
        ),
    ).awaitSuspending().rowCount() > 0

    @Suppress("TooGenericExceptionCaught") // a stored leg that no longer builds is a data gap, never a crash
    private fun Row.toDeal(): TreasuryDeal? {
        val legs: Result<List<LegSpec>?> = if (getString("legs_error") != null) {
            Result.success(null)
        } else {
            try {
                Result.success(
                    getString("legs")?.let { json ->
                        LegSpecJson.toLegs(
                            objectMapper.readValue(json, object : TypeReference<List<LegDto>>() {}),
                            LegDefaults(),
                            LegBounds.BOOKED,
                        )
                    },
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
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
            kind = DealKind.parse(getString("kind")),
            bookId = getString("book_id"),
            legs = legs.getOrNull(),
            scheduleHash = getString("schedule_hash"),
            legsError = getString("legs_error") ?: legs.exceptionOrNull()?.let {
                "legs cannot be built: ${it.message}"
            },
        )
    }

    private companion object {
        // Only rows that can be on the book at asOf: settled ones, and matured ones not yet past it.
        const val COLUMNS =
            "deal_id, product, counterparty_id, currency, principal, rate, value_date, maturity_date, state, " +
                "kind, book_id, legs::text AS legs, schedule_hash, legs_error"

        // ADR-0344 D2: only ACTUAL deals enter the snapshot's instruments.
        const val SELECT =
            "SELECT $COLUMNS FROM treasury_deal WHERE state IN ('SETTLED', 'MATURED') AND value_date <= $1 " +
                "AND kind = 'ACTUAL'"

        const val SELECT_CANDIDATES =
            "SELECT $COLUMNS FROM treasury_deal WHERE state <> 'REVERSED' AND value_date <= $1 " +
                "AND maturity_date > $1 " +
                "ORDER BY deal_id"

        const val RANK = "CASE %s WHEN 'BOOKED' THEN 1 WHEN 'SETTLED' THEN 2 ELSE 3 END"

        val UPSERT =
            "INSERT INTO treasury_deal (deal_id, product, counterparty_id, currency, principal, rate, value_date, " +
                "maturity_date, state, kind, book_id, legs, schedule_hash, legs_error) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12::jsonb, $13, $14) " +
                "ON CONFLICT (deal_id) DO UPDATE SET " +
                "state = CASE WHEN ${RANK.format("EXCLUDED.state")} > ${RANK.format("treasury_deal.state")} " +
                "THEN EXCLUDED.state ELSE treasury_deal.state END, " +
                "rate = COALESCE(treasury_deal.rate, EXCLUDED.rate), " +
                "value_date = COALESCE(treasury_deal.value_date, EXCLUDED.value_date), " +
                "maturity_date = COALESCE(treasury_deal.maturity_date, EXCLUDED.maturity_date), " +
                // ADR-0344 D2, monotonic like the rate: the first event carrying a field fills it,
                // a later or older one never overwrites it (amendments arrive as new flow versions).
                "book_id = COALESCE(treasury_deal.book_id, EXCLUDED.book_id), " +
                "legs = COALESCE(treasury_deal.legs, EXCLUDED.legs), " +
                "legs_error = CASE WHEN treasury_deal.legs IS NULL THEN EXCLUDED.legs_error " +
                "ELSE treasury_deal.legs_error END, " +
                "schedule_hash = COALESCE(treasury_deal.schedule_hash, EXCLUDED.schedule_hash), " +
                "updated_at = now() " +
                "WHERE ${RANK.format("EXCLUDED.state")} > ${RANK.format("treasury_deal.state")} " +
                "OR (treasury_deal.rate IS NULL AND EXCLUDED.rate IS NOT NULL) " +
                "OR (treasury_deal.value_date IS NULL AND EXCLUDED.value_date IS NOT NULL) " +
                "OR (treasury_deal.legs IS NULL AND EXCLUDED.legs IS NOT NULL) " +
                "OR (treasury_deal.book_id IS NULL AND EXCLUDED.book_id IS NOT NULL)"
    }
}
