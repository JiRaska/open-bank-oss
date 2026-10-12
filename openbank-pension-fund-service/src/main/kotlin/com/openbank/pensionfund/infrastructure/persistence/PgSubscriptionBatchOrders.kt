// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pensionfund.application.usecase.SubscriptionBatchLeg
import com.openbank.pensionfund.application.usecase.SubscriptionBatchOrder
import com.openbank.pensionfund.application.usecase.SubscriptionBatchStore
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.SqlConnection
import io.vertx.mutiny.sqlclient.Tuple
import io.vertx.pgclient.PgException
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Serializes a whole allocation and its idempotency receipt in one provider transaction. */
@Singleton
class PgSubscriptionBatchOrders(private val pool: Pool, private val mapper: ObjectMapper, private val clock: Clock) :
    SubscriptionBatchStore {
    override suspend fun place(
        contractId: UUID,
        batchKey: String,
        amount: BigDecimal,
        currency: String,
        legs: List<SubscriptionBatchLeg>,
    ): List<SubscriptionBatchOrder> {
        val fingerprint = fingerprint(amount, currency, legs)
        val result = legs.map { SubscriptionBatchOrder(UUID.randomUUID(), it.fundId, it.amount) }
        return pool.withTransaction { conn ->
            conn.preparedQuery(
                """INSERT INTO subscription_order_batches
                   (contract_id, batch_key, fingerprint, response)
                   VALUES ($1, $2, $3, $4::text::jsonb)
                   ON CONFLICT (contract_id, batch_key) DO NOTHING""",
            ).execute(Tuple.of(contractId, batchKey, fingerprint, mapper.writeValueAsString(result)))
                .flatMap { inserted ->
                    if (inserted.rowCount() == 0) {
                        readWinner(conn, contractId, batchKey, fingerprint)
                    } else {
                        lockAndInsert(conn, contractId, currency, legs, result)
                    }
                }
        }.onFailure { it is PgException && it.sqlState == "23505" }
            .transform { IllegalStateException("leg Idempotency-Key reused for a different order", it) }
            .awaitSuspending()
    }

    private fun readWinner(
        conn: SqlConnection,
        contractId: UUID,
        batchKey: String,
        fingerprint: String,
    ): Uni<List<SubscriptionBatchOrder>> = conn.preparedQuery(
        """SELECT fingerprint, response::text AS response FROM subscription_order_batches
           WHERE contract_id = $1 AND batch_key = $2""",
    ).execute(Tuple.of(contractId, batchKey)).map { rows ->
        val row = checkNotNull(rows.firstOrNull()) { "batch receipt was not visible after conflict" }
        check(row.getString("fingerprint") == fingerprint) { "batch Idempotency-Key reused for a different allocation" }
        mapper.readValue(row.getString("response"))
    }

    private fun lockAndInsert(
        conn: SqlConnection,
        contractId: UUID,
        currency: String,
        legs: List<SubscriptionBatchLeg>,
        result: List<SubscriptionBatchOrder>,
    ): Uni<List<SubscriptionBatchOrder>> {
        val locked = legs.map { it.fundId }.sortedBy(UUID::toString)
            .fold(Uni.createFrom().item(Unit)) { prior, fundId ->
                prior.flatMap {
                    conn.preparedQuery("SELECT status, currency FROM funds WHERE id = $1 FOR SHARE")
                        .execute(Tuple.of(fundId)).map { rows ->
                            val fund = checkNotNull(rows.firstOrNull()) { "fund $fundId was not found" }
                            check(fund.getString("status") == "ACTIVE") { "fund $fundId is closed" }
                            check(fund.getString("currency").trim() == currency) {
                                "fund $fundId is not denominated in $currency"
                            }
                        }
                }
            }
        return locked.flatMap {
            conn.preparedQuery(
                """INSERT INTO unit_orders
                   (id, contract_id, fund_id, order_type, amount, status, placed_at, idempotency_key)
                   VALUES ($1, $2, $3, 'SUBSCRIBE', $4, 'PENDING', $5, $6)""",
            ).executeBatch(
                legs.zip(result).map { (leg, order) ->
                    Tuple.tuple(
                        listOf(
                            order.id,
                            contractId,
                            leg.fundId,
                            leg.amount,
                            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                            leg.idempotencyKey,
                        ),
                    )
                },
            ).map { result }
        }
    }

    private fun fingerprint(amount: BigDecimal, currency: String, legs: List<SubscriptionBatchLeg>): String {
        val canonical = mapper.writeValueAsString(
            listOf(
                amount.setScale(2).toPlainString(),
                currency,
                legs.sortedBy { it.fundId.toString() }.map {
                    listOf(it.fundId.toString(), it.amount.setScale(2).toPlainString(), it.idempotencyKey)
                },
            ),
        )
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
