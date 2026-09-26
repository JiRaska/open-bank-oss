// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.domain.model.JournalSpec
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A ledger test double with the ledger's own idempotency contract: the first post of a key creates
 * a journal, every later post of the same key returns THAT journal's id and creates nothing.
 *
 * [loseNextResponse] simulates the dangerous half-failure: the ledger commits the journal but the
 * caller never sees the answer (timeout, dropped connection). The treasury service must then keep
 * the deal in its old state and, on retry, end up with ONE journal — not two.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeLedger : LedgerPostingPort {
    val journals = ConcurrentHashMap<String, Pair<UUID, JournalSpec>>()
    val calls = CopyOnWriteArrayList<String>()

    @Volatile
    var loseNextResponse = false

    override suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): UUID {
        calls += spec.idempotencyKey
        val (id, _) = journals.computeIfAbsent(spec.idempotencyKey) { UUID.randomUUID() to spec }
        if (loseNextResponse) {
            loseNextResponse = false
            throw jakarta.ws.rs.ProcessingException(
                "simulated: ledger committed ${spec.idempotencyKey} but the response was lost",
            )
        }
        return id
    }

    fun reset() {
        journals.clear()
        calls.clear()
        loseNextResponse = false
    }
}
