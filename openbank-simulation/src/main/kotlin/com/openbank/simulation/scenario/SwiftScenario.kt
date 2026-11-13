// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.adapters.AccountBookedChanged
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.World
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * One step of the outgoing SWIFT MT103 money path: a customer cross-border transfer driven
 * through the **real `SwiftMessage` domain object** (its `validate()` BIC/reference/amount/
 * charge-code/value-date gate and its PENDING → VALIDATED → SENT → ACKNOWLEDGED/COMPLETED /
 * REJECTED / FAILED status vocabulary), with the money moved through the **real `JournalEntry`
 * aggregate** — the ADR-0100 "build on the real system" convention, extending harness coverage
 * to the cross-border rail (issue #667's E2E money-path family).
 *
 * Lifecycle subtleties mirrored from production, because a re-model would miss them:
 *  - `validate()` is the admission gate: a seeded share of messages is born malformed (bad BIC
 *    or charge code), and the real validation output — not a simulated boolean — decides the
 *    REJECTED outcome. A refused message moves no money and books nothing.
 *  - Money moves only when the message is VALIDATED: funds are held on the ordering customer's
 *    balance, the double-entry journal posts, and the message goes SENT. A pre-commit write
 *    fault leaves nothing durable (FAILED, hold released); a post-commit conflict reverses the
 *    durable journal before the FAILED outcome is recorded.
 *  - A SENT message waits for its ACK: the seeded share that never receives one is FAILED and
 *    fully compensated (journal reversed, hold released); the share that does is ACKNOWLEDGED
 *    and settles COMPLETED in the same step. No message remains mid-flight once the step
 *    settles — `MoneyPathInvariants.swiftCompleteness`.
 */
object SwiftScenario {

    private const val MAX_AMOUNT_MINOR = 800_000L // up to 8000.00 — a modest cross-border transfer
    private const val MALFORMED_FRACTION = 10 // nextInt(10) < 2 ⇒ a 20% seeded malformed share
    private const val MALFORMED_THRESHOLD = 2
    private const val ACK_FRACTION = 10 // nextInt(10) < 8 ⇒ an 80% seeded ACK share
    private const val ACK_THRESHOLD = 8
    private val ZONE: ZoneId = ZoneId.of("Europe/Prague")
    private val VALUE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    // The same shared deposit-control GL the other scenarios post against; the customer
    // dimension is carried by JournalLine.subAccountId (what bookedDeltas() projects on).
    private val DEPOSIT_CONTROL_GL: UUID = UUID(0L, 1L)
    private const val NOSTRO_GL_SEQUENCE = 3L
    private val NOSTRO_GL: UUID = UUID(0L, NOSTRO_GL_SEQUENCE)
    private val SYSTEM_ACTOR: UUID = UUID(0L, 2L)

    fun step(world: World) {
        val random = world.context.random
        val now = world.context.clock.instant()
        val orderingAccount = world.customerAccounts[random.nextInt(world.customerAccounts.size)]
        val beneficiaryAccount = world.customerAccounts.first { it != orderingAccount }

        // 1. Build the real MT103. A seeded share is born malformed so the REAL validate()
        //    output decides a REJECTED outcome at the admission gate.
        val malformed = random.nextInt(MALFORMED_FRACTION) < MALFORMED_THRESHOLD
        val message = SwiftMessage(
            id = random.nextUuid(),
            idempotencyKey = "sim-swift-${random.nextUuid()}",
            messageType = SwiftMessageType.MT103,
            senderBic = if (malformed) "BAD" else "OPENCZPPXXX",
            receiverBic = "BENECZPPXXX",
            transactionReference = "sim-${random.nextUuid()}",
            relatedReference = null,
            valueDate = LocalDate.ofInstant(now, ZONE).format(VALUE_DATE_FORMAT),
            amount = Money.of(
                BigDecimal(random.nextLong(1L, MAX_AMOUNT_MINOR)).movePointLeft(2),
                world.currency,
            ),
            orderingCustomerAccount = orderingAccount.toString(),
            orderingCustomerAccountId = orderingAccount,
            orderingCustomerName = "Sim Ordering Customer",
            beneficiaryAccount = beneficiaryAccount.toString(),
            beneficiaryName = "Sim Beneficiary",
            remittanceInfo = null,
            chargeCode = if (malformed) "XXX" else "SHA",
            priority = SwiftPriority.NORMAL,
            status = SwiftStatus.PENDING,
            rawMt = null,
            ackReceivedAt = null,
            rejectionReason = null,
            createdAt = now,
            updatedAt = now,
        )
        world.swiftMessages.add(message)

        val validationErrors = message.validate()
        if (validationErrors.isNotEmpty()) {
            replaceMessage(
                world,
                message,
                message.copy(
                    status = SwiftStatus.REJECTED,
                    rejectionReason = validationErrors.joinToString(),
                    updatedAt = now,
                ),
            )
            world.audit.append("swift ${message.id} rejected at admission: ${validationErrors.joinToString()}")
            return
        }
        replaceMessage(world, message, message.copy(status = SwiftStatus.VALIDATED, updatedAt = now))
        settleMessage(world, message.id, now)
    }

    /**
     * Move and settle one VALIDATED MT103: hold the ordering customer's funds, post the
     * double-entry journal (customer debit, nostro credit) and drive the SENT → ACK →
     * COMPLETED lifecycle, or compensate to FAILED on a pre-commit write fault, a post-commit
     * conflict, or a missing ACK. Every non-completed exit path leaves nothing durable and
     * nothing held; only COMPLETED records money on both sides of [World.swiftBook].
     */
    private fun settleMessage(world: World, messageId: UUID, now: Instant) {
        val message = world.swiftMessages.first { it.id == messageId }
        val random = world.context.random
        val amount = message.amount
        val source = AccountCurrency(message.orderingCustomerAccountId!!, world.currency)

        // 2. Hold funds on the ordering customer's balance. A floor breach is a legitimate
        //    decline: the message fails and nothing moves.
        try {
            world.balances.put(world.balances.get(source).withReservation(amount.amount))
        } catch (_: IllegalArgumentException) {
            replaceMessage(
                world,
                message,
                message.copy(status = SwiftStatus.FAILED, rejectionReason = "insufficient funds", updatedAt = now),
            )
            world.audit.append("swift ${message.id} failed: insufficient funds")
            return
        }

        // 3. Post the balanced double-entry journal as the real aggregate: debit the ordering
        //    customer, credit the nostro GL (the money leaves the retail book).
        val entry: JournalEntry
        try {
            if (world.context.faults.shouldFailWrite()) {
                throw SimulatedWriteFailure("ledger post failed for swift ${message.id}")
            }
            entry = buildTransfer(world, message, source, amount, now)
            world.ledger.post("swift-${message.id}", entry)
            publishBooked(world, entry)
        } catch (e: SimulatedWriteFailure) {
            // Nothing durable: release the hold, the message fails.
            world.balances.put(world.balances.get(source).releaseReservation(amount.amount))
            replaceMessage(
                world,
                message,
                message.copy(status = SwiftStatus.FAILED, rejectionReason = e.message, updatedAt = now),
            )
            world.audit.append("swift ${message.id} compensated: ${e.message}")
            return
        }

        // 4. A post-commit fault forces the same compensation AFTER the journal is durable.
        if (world.context.faults.shouldConflict()) {
            val reversal = world.ledger.reverse(
                entry.id,
                random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { random.nextUuid() },
            )
            publishBooked(world, reversal)
            world.balances.put(world.balances.get(source).releaseReservation(amount.amount))
            replaceMessage(
                world,
                message,
                message.copy(status = SwiftStatus.FAILED, rejectionReason = "post-commit conflict", updatedAt = now),
            )
            world.audit.append("swift ${message.id} compensated: post-commit conflict")
            return
        }
        replaceMessage(world, message, message.copy(status = SwiftStatus.SENT, updatedAt = now))
        awaitAck(world, messageId, entry, source, amount, now)
    }

    /**
     * The SENT message waits for its ACK: the seeded share that never receives one is FAILED and
     * fully compensated (journal reversed, hold released); the share that does is ACKNOWLEDGED
     * and settles COMPLETED in the same step. Only COMPLETED records money on both sides of
     * [World.swiftBook].
     */
    private fun awaitAck(
        world: World,
        messageId: UUID,
        entry: JournalEntry,
        source: AccountCurrency,
        amount: Money,
        now: Instant,
    ) {
        val random = world.context.random
        val sent = world.swiftMessages.first { it.id == messageId }
        if (random.nextInt(ACK_FRACTION) >= ACK_THRESHOLD) {
            val reversal = world.ledger.reverse(
                entry.id,
                random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { random.nextUuid() },
            )
            publishBooked(world, reversal)
            world.balances.put(world.balances.get(source).releaseReservation(amount.amount))
            replaceMessage(
                world,
                sent,
                sent.copy(status = SwiftStatus.FAILED, rejectionReason = "no ACK within timeout", updatedAt = now),
            )
            world.audit.append("swift $messageId failed: no ACK, transfer compensated")
            return
        }
        // The journal is durable and the ACK arrived: the hold converts into the booked debit
        // (the same convention PaymentScenario step 4 follows), then the transfer completes.
        world.balances.put(world.balances.get(source).releaseReservation(amount.amount))
        world.swiftBook.recordPosted(messageId, amount.amount)
        val acked = world.swiftMessages.first { it.id == messageId }
            .copy(status = SwiftStatus.ACKNOWLEDGED, ackReceivedAt = now, updatedAt = now)
        val completed = acked.copy(status = SwiftStatus.COMPLETED, updatedAt = now)
        replaceMessage(world, world.swiftMessages.first { it.id == messageId }, completed)
        world.audit.append("swift $messageId completed (ACK)")
    }

    private fun replaceMessage(world: World, old: SwiftMessage, new: SwiftMessage) {
        val index = world.swiftMessages.indexOfFirst { it.id == old.id }
        world.swiftMessages[index] = new
    }

    /** Build the real POSTED double-entry transfer: debit ordering customer, credit nostro. */
    private fun buildTransfer(
        world: World,
        message: SwiftMessage,
        source: AccountCurrency,
        money: Money,
        now: Instant,
    ): JournalEntry {
        val random = world.context.random
        val journalId = random.nextUuid()
        val today = LocalDate.ofInstant(now, ZONE)
        val debit = JournalLine(
            id = lineId(journalId, 1L),
            journalId = journalId,
            glAccountId = DEPOSIT_CONTROL_GL,
            side = JournalSide.DEBIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 1,
            subAccountId = source.accountId,
        )
        val credit = JournalLine(
            id = lineId(journalId, 2L),
            journalId = journalId,
            glAccountId = NOSTRO_GL,
            side = JournalSide.CREDIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 2,
            subAccountId = NOSTRO_GL,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = message.id,
            entryDate = today,
            valueDate = today,
            description = "swift ${message.id} MT103 ${message.transactionReference}",
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = now,
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    private fun lineId(journalId: UUID, sequence: Long): UUID = UUID(journalId.mostSignificantBits, sequence)

    /** Project the entry's real `bookedDeltas()` onto the bus (dedup-guarded by the bus). */
    private fun publishBooked(world: World, entry: JournalEntry) {
        entry.bookedDeltas().forEach { delta ->
            world.bus.publish(
                AccountBookedChanged(entry.id, AccountCurrency(delta.accountId, delta.currency), delta.delta),
            )
        }
    }
}
