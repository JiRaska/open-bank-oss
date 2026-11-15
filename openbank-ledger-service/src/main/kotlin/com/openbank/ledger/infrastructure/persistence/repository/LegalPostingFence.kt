// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.LedgerConflictException
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.hibernate.reactive.mutiny.Mutiny
import java.time.LocalDate
import java.util.UUID

/** Check at the Ledger write boundary while holding the legal revision locks through commit. */
internal object LegalPostingFence {
    fun check(session: Mutiny.Session, entry: JournalEntry): Uni<Void> {
        val debits = entry.bookedDeltas().filter { it.delta.signum() < 0 }
        if (debits.isEmpty()) return Uni.createFrom().voidItem()
        val pockets = JsonArray(
            debits.map { JsonObject().put("account_id", it.accountId.toString()).put("currency", it.currency) },
        )
        // Bind the encoded JSON as text. Binding straight to jsonb makes the reactive driver
        // encode a Kotlin String as a JSON string instead of interpreting the encoded array.
        return session.createNativeQuery<Boolean>(
            """
            SELECT ledger_consume_legal_posting_permits(
              :journal, :payment, CAST(CAST(:intent AS text) AS jsonb), CAST(CAST(:pockets AS text) AS jsonb)
            )
            """.trimIndent(),
        ).setParameter("journal", entry.id)
            .setParameter("payment", entry.transactionId)
            .setParameter("intent", intent(entry).encode())
            .setParameter("pockets", pockets.encode())
            .singleResult
            .invoke { allowed ->
                if (!allowed) throw LedgerConflictException("Current legal posting authorization is required")
            }.replaceWithVoid()
    }

    private fun intent(entry: JournalEntry): JsonObject = intent(
        entry.entryDate,
        entry.valueDate,
        entry.synthetic,
        entry.reversalOf,
        entry.lines.sortedBy { it.sequence }.map { line ->
            JournalLineRequest(
                glAccountId = line.glAccountId,
                side = line.side,
                amount = line.amount.amount,
                currencyCode = line.amount.currency.code,
                fxRate = line.fxRate,
                baseAmount = line.baseAmount.amount,
                baseCurrencyCode = line.baseAmount.currency.code,
                subAccountId = line.subAccountId,
            )
        },
    )

    fun intent(command: PostJournalCommand): JsonObject =
        intent(command.entryDate, command.valueDate, command.synthetic, null, command.lines)

    /** Include both sides, dates and reversal linkage; a permit cannot be redirected to another leg. */
    private fun intent(
        entryDate: LocalDate,
        valueDate: LocalDate,
        synthetic: Boolean,
        reversalOf: UUID?,
        lines: List<JournalLineRequest>,
    ): JsonObject = JsonObject()
        .put("version", 1)
        .put("synthetic", synthetic)
        .put("entry_date", entryDate.toString())
        .put("value_date", valueDate.toString())
        .put("reversal_of", reversalOf?.toString())
        .put("lines", JsonArray(lines.map(::lineIntent)))

    private fun lineIntent(line: JournalLineRequest): JsonObject = JsonObject()
        .put("gl_account_id", line.glAccountId.toString())
        .put("side", line.side.name)
        .put("amount", line.amount)
        .put("currency", line.currencyCode)
        .put("base_amount", line.baseAmount)
        .put("base_currency", line.baseCurrencyCode)
        .put("fx_rate", line.fxRate)
        .put("sub_account_id", line.subAccountId?.toString())
}
