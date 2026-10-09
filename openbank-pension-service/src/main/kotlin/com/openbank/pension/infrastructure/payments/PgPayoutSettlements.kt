// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.openbank.pension.application.exit.InstructionStatus
import com.openbank.pension.application.exit.PaymentInstruction
import com.openbank.pension.application.usecase.PayoutSettlementRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Singleton
import java.time.Clock
import java.time.ZoneOffset

/**
 * Settlement read/write on `pension_payment_instructions` over the plain reactive pool: the caller
 * is a Kafka consumer, where no Hibernate session exists. The status guard is in the UPDATE itself
 * (`status = ANY($4)`), so two redeliveries racing each other change the row once.
 */
@Singleton
class PgPayoutSettlements(private val client: Pool, private val clock: Clock) : PayoutSettlementRepository {

    override suspend fun findByPaymentRef(paymentRef: String): PaymentInstruction? = client.preparedQuery(
        """
            SELECT idempotency_key, contract_id, purpose, amount, currency, creditor_iban, status, payment_ref
            FROM pension_payment_instructions WHERE payment_ref = $1
        """.trimIndent(),
    ).execute(Tuple.of(paymentRef)).awaitSuspending().firstOrNull()?.let { row ->
        PaymentInstruction(
            row.getString("idempotency_key"),
            row.getUUID("contract_id"),
            row.getString("purpose"),
            row.getBigDecimal("amount"),
            row.getString("currency"),
            row.getString("creditor_iban"),
            InstructionStatus.valueOf(row.getString("status")),
            row.getString("payment_ref"),
        )
    }

    override suspend fun markSettlement(
        paymentRef: String,
        from: Set<InstructionStatus>,
        to: InstructionStatus,
    ): Boolean = client.preparedQuery(
        """
            UPDATE pension_payment_instructions SET status = $1, updated_at = $2
            WHERE payment_ref = $3 AND status = ANY($4)
        """.trimIndent(),
    ).execute(
        Tuple.of(
            to.name,
            clock.instant().atOffset(ZoneOffset.UTC),
            paymentRef,
            from.map {
                it.name
            }.toTypedArray(),
        ),
    ).awaitSuspending().rowCount() > 0
}
