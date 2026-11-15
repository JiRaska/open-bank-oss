// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.LedgerConflictException
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.hibernate.reactive.mutiny.Mutiny

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

    /** Include both sides, dates and reversal linkage; a permit cannot be redirected to another leg. */
    private fun intent(entry: JournalEntry): JsonObject = JsonObject()
        .put("version", 1)
        .put("synthetic", entry.synthetic)
        .put("entry_date", entry.entryDate.toString())
        .put("value_date", entry.valueDate.toString())
        .put("reversal_of", entry.reversalOf?.toString())
        .put("lines", JsonArray(entry.lines.sortedBy { it.sequence }.map(::lineIntent)))

    private fun lineIntent(line: JournalLine): JsonObject = JsonObject()
        .put("gl_account_id", line.glAccountId.toString())
        .put("side", line.side.name)
        .put("amount", line.amount.amount)
        .put("currency", line.amount.currency.code)
        .put("base_amount", line.baseAmount.amount)
        .put("base_currency", line.baseAmount.currency.code)
        .put("fx_rate", line.fxRate)
        .put("sub_account_id", line.subAccountId?.toString())
}
