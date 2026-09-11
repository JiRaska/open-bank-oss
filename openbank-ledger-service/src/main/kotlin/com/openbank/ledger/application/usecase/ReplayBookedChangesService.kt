// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.`in`.ReplayBookedChangesCommand
import com.openbank.ledger.application.port.`in`.ReplayBookedChangesResult
import com.openbank.ledger.application.port.`in`.ReplayBookedChangesUseCase
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.domain.event.AccountBookedChangedEvent
import com.openbank.ledger.domain.model.AccountBookedDelta
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Re-emits historical `AccountBookedChanged` events for a date window (issue #860). It reconstructs
 * each event exactly as the original journal post did — via [JournalEntry.bookedDeltas], the same
 * credit-minus-base-amount logic — and enqueues it on the transactional outbox, so the existing
 * dispatcher relays it to `openbank.ledger.journal.posted` under the `ce-type` header the downstream
 * projection filters on. Posts NO journal and mutates NO ledger state.
 *
 * Idempotency is the consumer's: it dedups on `(journalEntryId, accountId, currency)`, so a replay
 * lands only the deltas it never applied — re-running is safe.
 */
@ApplicationScoped
class ReplayBookedChangesService(
    private val journalRepository: JournalRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : ReplayBookedChangesUseCase {
    private val log = Logger.getLogger(ReplayBookedChangesService::class.java)

    override suspend fun replay(command: ReplayBookedChangesCommand): ReplayBookedChangesResult {
        require(!command.from.isAfter(command.to)) {
            "from (${command.from}) must not be after to (${command.to})"
        }
        val tally = Tally()
        var afterId: UUID? = null
        while (true) {
            val page = journalRepository.findByDateRange(command.from, command.to, PAGE_SIZE, afterId)
            page.forEach { tally.accumulate(it, command.dryRun) }
            if (page.size < PAGE_SIZE) break
            afterId = page.last().id
        }

        if (!command.dryRun && tally.messages.isNotEmpty()) {
            journalRepository.appendOutbox(tally.messages)
            log.warnf(
                "Booked-change replay ENQUEUED %d AccountBookedChanged events " +
                    "(%d journal entries, %d accounts, window %s..%s) for downstream re-projection (#860).",
                tally.events,
                tally.scanned,
                tally.accounts.size,
                command.from,
                command.to,
            )
        } else {
            log.infof(
                "Booked-change replay DRY-RUN: %d events / %d entries / %d accounts in %s..%s; nothing emitted.",
                tally.events,
                tally.scanned,
                tally.accounts.size,
                command.from,
                command.to,
            )
        }
        return tally.toResult(command)
    }

    /** Builds the outbox message for one booked delta, identical to the original post's emission. */
    private fun replayMessage(entry: JournalEntry, delta: AccountBookedDelta): OutboxMessage {
        val event = AccountBookedChangedEvent(
            aggregateId = delta.accountId,
            version = entry.version,
            currency = delta.currency,
            delta = delta.delta,
            journalEntryId = entry.id,
            transactionId = entry.transactionId,
            entryDate = entry.entryDate,
            occurredAt = clock.instant(),
        )
        return OutboxMessage(
            aggregateId = delta.accountId,
            eventType = event.eventType,
            // "Identical to the original post's emission" has to include the taint: a replay that
            // dropped it would re-emit a canary's movements as real ones, laundering exactly the
            // activity ADR-0252 exists to keep out of the aggregates. Readable only since the
            // dimension landed on journal_entries (V26); before that the entry could not say.
            synthetic = entry.synthetic,
            payload = objectMapper.writeValueAsString(event),
        )
    }

    /** Running totals for a replay pass; keeps the paging loop flat. */
    private inner class Tally {
        var scanned = 0
        var events = 0
        val accounts = mutableSetOf<UUID>()
        val netByCurrency = mutableMapOf<String, BigDecimal>()
        val messages = mutableListOf<OutboxMessage>()

        fun accumulate(entry: JournalEntry, dryRun: Boolean) {
            scanned++
            entry.bookedDeltas().forEach { delta ->
                events++
                accounts += delta.accountId
                netByCurrency.merge(delta.currency, delta.delta, BigDecimal::add)
                if (!dryRun) messages += replayMessage(entry, delta)
            }
        }

        fun toResult(command: ReplayBookedChangesCommand) = ReplayBookedChangesResult(
            dryRun = command.dryRun,
            from = command.from,
            to = command.to,
            journalEntriesScanned = scanned,
            bookedChangeEvents = events,
            accountsTouched = accounts.size,
            netDeltaByCurrency = netByCurrency,
        )
    }

    private companion object {
        // Cursor page size for scanning posted entries in the window; bounds memory on a wide replay.
        const val PAGE_SIZE = 500
    }
}
