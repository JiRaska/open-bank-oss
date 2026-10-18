// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.model.SwiftSettlementPosting
import com.openbank.simulation.runner.World
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Money-path SWIFT adoption (ADR-0104 D4 / ADR-0108): one step of the cross-border message path,
 * modelled directly against the **real `swift-service` `SwiftMessage`** aggregate and its real
 * `validate()` admission — not a re-model of either. The domain package is framework-free
 * (ADR-0002), so only the domain POJO lands on the classpath.
 *
 * SWIFT is multi-currency (the `Money` carries any ISO 4217 currency), so the settlement leg
 * posts a real balanced `JournalEntry` in the message's own currency: the conservation invariant
 * nets per currency (each entry balances), and the ledger→balance projection invariant only
 * asserts over opened balances, so an unprojected EUR/USD/GBP entry on GL accounts is not a
 * projection gap — cross-border settlement is a correspondent-level movement the customer's CZK
 * balance projection never sees.
 *
 * A step mirrors the production transition mapping of `SwiftService.send` → `submitToScheme` →
 * `settle` (documented here because the use-case is suspend/Mutiny-coupled, same precedent as
 * ClearingCycleScenario's cycle mapping):
 *  0. an idempotent replay (`findByIdempotencyKey` hit) returns the existing message and posts
 *     nothing — the step is a no-op;
 *  1. admission through the real `validate()` (BIC 8..11, non-blank transaction reference,
 *     positive amount, OUR/SHA/BEN, parseable `yyyyMMdd` value date) — the message is VALIDATED;
 *  2. a scheme verdict reject (pacs.002) → REJECTED before any money moved;
 *  3. an injected write fault at the settlement post → the gateway-unavailable production hold is
 *     mapped to the operator reject (`SwiftService.reject`) so the step ends terminal — no journal;
 *  4. a post-commit conflict after the journal is durable → the real `JournalEntry.reverse()`
 *     unwinds it and the message dies in FAILED — money conserved end-to-end;
 *  5. happy path: the balanced journal posts once, the message COMPLETEDs, and with a small
 *     chance the operator acknowledgement (`SwiftService.acknowledge`) stamps it ACKNOWLEDGED.
 *
 * Every message reaches a terminal status within the step, which is what
 * `MoneyPathInvariants.swiftCompleteness` asserts after the scheduler drains.
 */
object SwiftMessageScenario {

    private const val MAX_MINOR_UNITS = 500_000L // up to 5000.00 in a 2-dp currency
    private const val IDEMPOTENT_REPLAY_RATE = 0.05
    private const val SCHEME_REJECT_RATE = 0.08
    private const val OPERATOR_ACK_RATE = 0.15
    private const val IBAN_PREFIX_LENGTH = 24

    // A single shared correspondent-settlement GL pair; the customer dimension is carried by
    // JournalLine.subAccountId, exactly as SctInstPaymentScenario's scheme-settlement entry does.
    // Named least-significant-bits, same convention as the other scenarios' GL constants.
    private const val SWIFT_SETTLEMENT_GL_LSB = 21L
    private const val SYSTEM_ACTOR_LSB = 22L
    private const val CORRESPONDENT_HOUSE_LSB = 23L
    private val SWIFT_SETTLEMENT_GL: UUID = UUID(0L, SWIFT_SETTLEMENT_GL_LSB)
    private val SYSTEM_ACTOR: UUID = UUID(0L, SYSTEM_ACTOR_LSB)
    private val CORRESPONDENT_HOUSE: UUID = UUID(0L, CORRESPONDENT_HOUSE_LSB)

    private val BICS = listOf("DEUTDEFF", "CHASUS33", "BNPAFRPP", "BOFAUS3NXXX", "INGBNL2A")
    private val CHARGE_CODES = listOf("OUR", "SHA", "BEN")
    private val SETTLEMENT_CURRENCIES = listOf("EUR", "USD", "GBP")
    private val VALUE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun step(world: World) {
        // 0. An idempotent replay returns the persisted message and posts nothing.
        if (world.swift.messages.isNotEmpty() && world.context.random.chance(IDEMPOTENT_REPLAY_RATE)) {
            world.audit.append("swift idempotent replay: no-op")
            return
        }
        val leg = openLeg(world)
        val journalId = world.context.random.nextUuid()

        // 2. A scheme verdict reject (pacs.002) means no money ever moved.
        if (schemeReject(world, leg)) return

        // 3. An injected write fault at the settlement post: reject before any money moved.
        if (settle(world, leg, journalId)) return

        // 4. A post-commit conflict after the journal is durable: unwind and fail.
        if (conflict(world, leg, journalId)) return

        // 5. Happy path: one balanced journal is durable and the message completes.
        var done = leg.advance(world, SwiftStatus.COMPLETED)
        if (world.context.random.chance(OPERATOR_ACK_RATE)) {
            done = done.advance(world, SwiftStatus.ACKNOWLEDGED, ack = true)
        }
    }

    private fun openLeg(world: World): SwiftLeg {
        val random = world.context.random
        val minorUnits = random.nextLong(1L, MAX_MINOR_UNITS)
        val currency = random.pick(SETTLEMENT_CURRENCIES)
        // The real companion factory: minor units at the currency's own scale.
        val amount = SwiftMessage.moneyOfMinorUnits(minorUnits, currency)
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        val now = world.context.clock.instant()

        val pending = SwiftMessage(
            id = random.nextUuid(),
            idempotencyKey = "swift-${random.nextUuid()}",
            messageType = SwiftMessageType.MT103,
            senderBic = random.pick(BICS),
            receiverBic = random.pick(BICS),
            transactionReference = "trn-${random.nextUuid()}",
            relatedReference = null,
            valueDate = today.format(VALUE_DATE_FORMAT),
            amount = amount,
            orderingCustomerAccount = "CZ6508000000192000145399",
            orderingCustomerAccountId = world.customerAccounts[random.nextInt(world.customerAccounts.size)],
            orderingCustomerName = "Simulated Ordering Customer",
            beneficiaryAccount = "DE0000000000${random.nextUuid()}".take(IBAN_PREFIX_LENGTH),
            beneficiaryName = "Simulated Beneficiary",
            remittanceInfo = null,
            chargeCode = random.pick(CHARGE_CODES),
            priority = random.pick(SwiftPriority.entries),
            status = SwiftStatus.PENDING,
            rawMt = null,
            ackReceivedAt = null,
            rejectionReason = null,
            createdAt = now,
            updatedAt = now,
        )
        // The real admission rule: production's `require(errors.isEmpty())` — a message that
        // fails validation never persists, so the scenario builds only from valid pools and
        // asserts the real validate() agrees (the negative cases live in the unit test).
        check(pending.validate().isEmpty()) { "simulated SWIFT message failed the real validate()" }
        world.swift.recordMessage(pending)
        return SwiftLeg(pending, world.swift.messages.lastIndex)
            .advance(world, SwiftStatus.VALIDATED)
    }

    /** The scheme's pacs.002 reject; returns true when the message died before any money moved. */
    private fun schemeReject(world: World, leg: SwiftLeg): Boolean {
        if (!world.context.random.chance(SCHEME_REJECT_RATE)) return false
        leg.advance(world, SwiftStatus.REJECTED, rejection = "scheme reject (pacs.002): simulated")
        return true
    }

    /** Posts the settlement journal; returns true when a write fault rejected the message. */
    private fun settle(world: World, leg: SwiftLeg, journalId: UUID): Boolean = try {
        if (world.context.faults.shouldFailWrite()) {
            throw SimulatedWriteFailure("swift settlement post failed for ${leg.message.id}")
        }
        val entry = buildSettlementEntry(world, journalId, leg.message)
        world.ledger.post("swift-${leg.message.id}-settlement", entry)
        world.swift.recordPosting(
            SwiftSettlementPosting(
                messageId = leg.message.id,
                journalId = journalId,
                amount = leg.message.amount.amount,
                currency = leg.message.currency,
            ),
        )
        false
    } catch (e: SimulatedWriteFailure) {
        // Production holds the message in VALIDATED on gateway unavailability (retried
        // externally); the step maps the hold to the operator reject so it ends terminal.
        leg.advance(world, SwiftStatus.REJECTED, rejection = "gateway unavailable: ${e.message}")
        true
    }

    /** Post-commit conflict: the real reverse unwinds the journal and the message fails. */
    private fun conflict(world: World, leg: SwiftLeg, journalId: UUID): Boolean {
        if (!world.context.faults.shouldConflict()) return false
        world.ledger.reverse(
            journalId,
            world.context.random.nextUuid(),
            SYSTEM_ACTOR,
            lineIdProvider = { world.context.random.nextUuid() },
        )
        val posting = world.swift.postingOf(leg.message.id)
        check(posting != null) { "conflicted swift message ${leg.message.id} has no settlement posting" }
        world.swift.replacePosting(posting.copy(reversed = true))
        leg.advance(world, SwiftStatus.FAILED, rejection = "POST_COMMIT_CONFLICT")
        return true
    }

    private fun buildSettlementEntry(world: World, journalId: UUID, message: SwiftMessage): JournalEntry {
        val amount = message.amount
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        val debit = JournalLine(
            id = lineId(journalId, 1L),
            journalId = journalId,
            glAccountId = SWIFT_SETTLEMENT_GL,
            side = JournalSide.DEBIT,
            amount = amount,
            fxRate = null,
            baseAmount = amount,
            sequence = 1,
            subAccountId = message.orderingCustomerAccountId,
        )
        val credit = JournalLine(
            id = lineId(journalId, 2L),
            journalId = journalId,
            glAccountId = SWIFT_SETTLEMENT_GL,
            side = JournalSide.CREDIT,
            amount = amount,
            fxRate = null,
            baseAmount = amount,
            sequence = 2,
            subAccountId = CORRESPONDENT_HOUSE,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = message.id,
            entryDate = today,
            valueDate = today,
            description = "swift settlement ${message.id}",
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    private fun lineId(journalId: UUID, sequence: Long): UUID = UUID(journalId.mostSignificantBits, sequence)

    /** The real domain aggregate driven by one scenario step, with its index into [World]. */
    private data class SwiftLeg(val message: SwiftMessage, val index: Int) {
        fun advance(world: World, to: SwiftStatus, ack: Boolean = false, rejection: String? = null): SwiftLeg {
            val now = world.context.clock.instant()
            val next = message.copy(
                status = to,
                ackReceivedAt = if (ack) now else message.ackReceivedAt,
                rejectionReason = rejection ?: message.rejectionReason,
                updatedAt = now,
            )
            world.swift.replaceMessage(next)
            world.audit.append("swift ${next.id} -> ${next.status}")
            return copy(message = next)
        }
    }
}
