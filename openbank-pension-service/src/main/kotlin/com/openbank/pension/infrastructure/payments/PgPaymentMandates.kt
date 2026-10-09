// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.openbank.pension.application.usecase.PaymentMandate
import com.openbank.pension.application.usecase.PaymentMandateRepository
import com.openbank.pension.application.usecase.PaymentMandateStatus
import com.openbank.pension.domain.contribution.MandateKind
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Singleton
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `pension_payment_mandates` (V7) over the reactive pool, the S3 funding stores' idiom: the
 * idempotency guarantee is the `ON CONFLICT DO NOTHING` statement itself, so a retried set-up
 * that races its first attempt still records one row.
 */
@Singleton
class PgPaymentMandates(private val client: Pool) : PaymentMandateRepository {

    override suspend fun recordIfAbsent(mandate: PaymentMandate): PaymentMandate {
        client.preparedQuery(
            """
            INSERT INTO pension_payment_mandates (id, contract_id, kind, external_id, status, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            ON CONFLICT (kind, external_id) DO NOTHING
            """.trimIndent(),
        ).execute(
            Tuple.from(
                listOf(
                    mandate.id,
                    mandate.contractId,
                    mandate.kind.name,
                    mandate.externalId,
                    mandate.status.name,
                    mandate.createdAt.atOffset(ZoneOffset.UTC),
                    mandate.updatedAt.atOffset(ZoneOffset.UTC),
                ),
            ),
        ).awaitSuspending()
        return checkNotNull(
            one(
                "SELECT * FROM pension_payment_mandates WHERE kind = $1 AND external_id = $2",
                Tuple.of(mandate.kind.name, mandate.externalId),
            ),
        ) { "mandate ${mandate.kind}/${mandate.externalId} vanished after insert" }
    }

    override suspend fun findById(id: UUID): PaymentMandate? =
        one("SELECT * FROM pension_payment_mandates WHERE id = $1", Tuple.of(id))

    override suspend fun markCancelled(id: UUID, at: Instant) {
        client.preparedQuery(
            "UPDATE pension_payment_mandates SET status = 'CANCELLED', updated_at = $2 WHERE id = $1",
        ).execute(Tuple.of(id, at.atOffset(ZoneOffset.UTC))).awaitSuspending()
    }

    private suspend fun one(sql: String, args: Tuple): PaymentMandate? =
        client.preparedQuery(sql).execute(args).awaitSuspending().firstOrNull()?.toMandate()

    private fun Row.toMandate() = PaymentMandate(
        id = getUUID("id"),
        contractId = getUUID("contract_id"),
        kind = MandateKind.valueOf(getString("kind")),
        externalId = getString("external_id"),
        status = PaymentMandateStatus.valueOf(getString("status")),
        createdAt = get(OffsetDateTime::class.java, "created_at").toInstant(),
        updatedAt = get(OffsetDateTime::class.java, "updated_at").toInstant(),
    )
}
