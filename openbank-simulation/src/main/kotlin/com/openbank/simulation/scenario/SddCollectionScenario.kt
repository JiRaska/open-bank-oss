// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.sdd.domain.authorise.AuthorisationResult
import com.openbank.sdd.domain.authorise.CollectionAuthorisationPolicy
import com.openbank.sdd.domain.authorise.CollectionInstruction
import com.openbank.sdd.domain.authorise.DebtorControls
import com.openbank.sdd.domain.lifecycle.MandateLifecycle
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.sdd.domain.refund.RefundDecision
import com.openbank.sdd.domain.refund.RefundPolicy
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.model.SddCollectionRecord
import com.openbank.simulation.model.SddCollectionStatus
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Money-path SEPA Direct Debit adoption (ADR-0036, EPC Core/B2B rulebooks): one step of the
 * mandate + collection + refund path, modelled directly against the **real sdd-service domain** —
 * the `SddMandate` aggregate, the pure `MandateLifecycle` state machine, the fail-closed
 * `CollectionAuthorisationPolicy`, and the `RefundPolicy` window rules — not a re-model of any of
 * them. The domain package is framework-free (ADR-0002), so only the domain POJOs land on the
 * classpath.
 *
 * A step:
 *  1. **Mandate lifecycle** — create a new mandate (Core born ACTIVE; B2B confirmed in the same
 *     step through the real `MandateLifecycle.confirm`, mirroring the debtor-bank confirmation as
 *     part of setup, so no mandate can park in PENDING_CONFIRMATION forever) or drive a lifecycle
 *     event on a random live mandate through the REAL state machine (`suspend`/`resume`/`cancel`/
 *     `amend`). A backdated seed fraction makes the EPC 36-month idle expiry reachable; the sweep
 *     then applies the real `MandateLifecycle.expireIfIdle` to every live mandate every step —
 *     the production `MandateExpiryScheduler` shape, with no RNG draws.
 *  2. **Collection** — present a real `CollectionInstruction` against a random mandate (live or
 *     terminal: the policy must decide) with seeded `DebtorControls`, and let the REAL
 *     `CollectionAuthorisationPolicy` decide: Reject (MD01/FF05 — no/inactive mandate, scheme
 *     mismatch, non-EUR, unverified B2B, one-off reuse), Refuse (MS02 — debtor controls), or
 *     Accept. An accepted collection posts the EUR debit journal (SEPA DD is EUR-only, so both
 *     legs are EUR on the scheme-settlement GL with the debtor as the debit line's customer
 *     dimension — the customer's CZK balance projection never sees an EUR account, exactly like
 *     [SctInstPaymentScenario]'s EUR legs). An injected write fault fails the collection before
 *     any money moved; a duplicate delivery of the debit command collapses onto the one booked
 *     journal (the deterministic `sdd-debit-{id}` key); a post-commit conflict reverses the
 *     durable journal via the real `JournalEntry.reverse()` and records the collection REVERSED;
 *     the happy path records it SETTLED and stamps the mandate through the real
 *     `recordCollection` (FRST → RCUR + lastCollectionDate, which also drives idle expiry).
 *  3. **Refund** — a seeded fraction of settled collections draws a claim date inside or outside
 *     the real `RefundPolicy` windows (Core 8-week unconditional / B2B none); an Eligible claim
 *     posts the EUR refund journal (the exact reverse legs). A write fault abandons the claim
 *     before it registers, so the book never owes a refund it didn't pay.
 *
 * `MoneyPathInvariants.sddCompleteness` and `sddMoneyConservation` assert the whole book after
 * every step.
 */
object SddCollectionScenario {

    private const val SCHEME_CURRENCY = "EUR" // CollectionAuthorisationPolicy rejects anything else (FF05)
    private const val MAX_MINOR_UNITS = 500_000L // up to 5000.00 EUR per collection
    private const val IBAN_PREFIX_LENGTH = 24
    private const val NEW_MANDATE_RATE = 0.30
    private const val LIFECYCLE_EVENT_RATE = 0.50
    private const val BACKDATED_MANDATE_RATE = 0.15
    private const val REFUND_CLAIM_RATE = 0.30
    private const val BLOCK_ALL_RATE = 0.06
    private const val BLOCKED_CREDITOR_RATE = 0.06
    private const val CAP_RATE = 0.08
    private const val LIFECYCLE_EVENT_KINDS = 3 // suspend/resume, cancel, amend
    private const val MAX_CLAIM_OFFSET_DAYS = 400L // straddles the 56-day Core window and the 13-month rule
    private const val SDD_SETTLEMENT_GL_LSB = 15L
    private const val SYSTEM_ACTOR_LSB = 16L
    private const val SCHEME_HOUSE_LSB = 17L
    private const val TECHNICAL_ERROR = "TECHNICAL_ERROR"
    private val SDD_SETTLEMENT_GL: UUID = UUID(0L, SDD_SETTLEMENT_GL_LSB)
    private val SYSTEM_ACTOR: UUID = UUID(0L, SYSTEM_ACTOR_LSB)
    private val SCHEME_HOUSE: UUID = UUID(0L, SCHEME_HOUSE_LSB)

    fun step(world: World) {
        val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        driveMandateLifecycle(world, now, today)
        driveCollection(world, today)
    }

    // 1. Mandate lifecycle: create, or drive a real state-machine event, then the idle-expiry sweep.
    private fun driveMandateLifecycle(world: World, now: OffsetDateTime, today: LocalDate) {
        val random = world.context.random
        when {
            random.chance(NEW_MANDATE_RATE) -> world.sdd.recordMandate(newMandate(world, now, today))
            random.chance(LIFECYCLE_EVENT_RATE) -> {
                val live = world.sdd.mandates.filter { !it.isTerminal }
                if (live.isNotEmpty()) lifecycleEvent(world, random.pick(live), now)
            }
        }
        // The MandateExpiryScheduler shape: the real expireIfIdle over every live mandate — a
        // pure function of the mandate dates and today, no RNG draws.
        world.sdd.mandates.filter { !it.isTerminal }.forEach { mandate ->
            val expired = MandateLifecycle.expireIfIdle(mandate, today)
            if (expired != mandate) {
                world.sdd.replaceMandate(expired)
                world.audit.append(
                    "sdd mandate ${expired.id} EXPIRED: idle ${MandateLifecycle.IDLE_EXPIRY_MONTHS} months",
                )
            }
        }
    }

    private fun newMandate(world: World, now: OffsetDateTime, today: LocalDate): SddMandate {
        val random = world.context.random
        val accountId = random.pick(world.customerAccounts)
        val scheme = if (random.nextInt(2) == 0) SddScheme.CORE else SddScheme.B2B
        val signatureDate = if (random.chance(BACKDATED_MANDATE_RATE)) {
            // 37+ months idle so the real expireIfIdle fires on this or a later sweep.
            today.minusMonths(MandateLifecycle.IDLE_EXPIRY_MONTHS + 1L)
        } else {
            today
        }
        val born = SddMandate(
            id = random.nextUuid(),
            accountId = accountId,
            debtorIban = iban(accountId),
            creditorIdentifier = "CREDITOR-${random.nextUuid()}",
            umr = "UMR-${random.nextUuid()}",
            scheme = scheme,
            sequenceType = if (random.nextInt(2) == 0) SequenceType.FRST else SequenceType.RCUR,
            creditorName = "Simulated Creditor",
            debtorName = "Simulated Debtor",
            signatureDate = signatureDate,
            status = if (scheme == SddScheme.B2B) MandateStatus.PENDING_CONFIRMATION else MandateStatus.ACTIVE,
            b2bConfirmed = false,
            lastCollectionDate = null,
            lastPreNotificationDate = null,
            createdAt = now.toInstant(),
        )
        // The debtor-bank confirmation is part of B2B setup: confirm in-step through the real
        // state machine so no mandate can park in PENDING_CONFIRMATION forever.
        val mandate = if (scheme == SddScheme.B2B) MandateLifecycle.confirm(born) else born
        world.audit.append("sdd mandate ${mandate.id} created ($scheme, ${mandate.sequenceType})")
        return mandate
    }

    private fun lifecycleEvent(world: World, mandate: SddMandate, now: OffsetDateTime) {
        val random = world.context.random
        val updated = when (random.nextInt(LIFECYCLE_EVENT_KINDS)) {
            0 -> if (mandate.status == MandateStatus.ACTIVE) {
                MandateLifecycle.suspend(mandate)
            } else {
                MandateLifecycle.resume(mandate)
            }
            1 -> MandateLifecycle.cancel(mandate)
            else -> MandateLifecycle.amend(
                mandate,
                field = "creditorName",
                oldValue = mandate.creditorName,
                newValue = "Amended Creditor",
                asOf = now.toInstant(),
            )
        }
        world.sdd.replaceMandate(updated)
        val event = when {
            updated.status != mandate.status -> updated.status.name
            updated.amendments.size > mandate.amendments.size -> "AMENDED"
            else -> "UNCHANGED"
        }
        world.audit.append("sdd mandate ${mandate.id} -> $event")
    }

    // 2. + 3. Collection against a random mandate through the real authorisation policy, then the
    // debit (and, for a seeded fraction of settled ones, the refund) money legs.
    private fun driveCollection(world: World, today: LocalDate) {
        val random = world.context.random
        if (world.sdd.mandates.isEmpty()) return
        val mandate = random.pick(world.sdd.mandates)
        val amount = BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2)
        val instruction = CollectionInstruction(
            creditorIdentifier = mandate.creditorIdentifier,
            umr = mandate.umr,
            scheme = mandate.scheme,
            sequenceType = mandate.sequenceType,
            amount = amount,
            currency = SCHEME_CURRENCY,
            dueDate = today,
        )
        val controls = when {
            random.chance(BLOCK_ALL_RATE) -> DebtorControls(blockAll = true)
            random.chance(BLOCKED_CREDITOR_RATE) ->
                DebtorControls(blockedCreditors = setOf(mandate.creditorIdentifier))
            random.chance(CAP_RATE) -> DebtorControls(
                maxAmountPerCollection = BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2),
            )
            else -> DebtorControls()
        }
        when (val decision = CollectionAuthorisationPolicy.authorise(mandate, instruction, controls)) {
            is AuthorisationResult.Reject ->
                record(world, mandate, amount, SddCollectionStatus.REJECTED, decision.reasonCode)

            is AuthorisationResult.Refuse ->
                record(world, mandate, amount, SddCollectionStatus.REFUSED, decision.reasonCode)

            AuthorisationResult.Accept ->
                postDebitLeg(world, mandate, instruction, today)
        }
    }

    private fun postDebitLeg(world: World, mandate: SddMandate, instruction: CollectionInstruction, today: LocalDate) {
        val random = world.context.random
        val collectionId = random.nextUuid()
        var entry: JournalEntry? = null
        val settled = try {
            if (world.context.faults.shouldFailWrite()) {
                throw SimulatedWriteFailure("sdd debit post failed for mandate ${mandate.id}")
            }
            val journalId = random.nextUuid()
            buildEurEntry(
                world,
                journalId,
                collectionId,
                description = "sdd collection ${mandate.umr}",
                amount = instruction.amount,
                debtorAccount = mandate.accountId,
                refund = false,
            ).also { entry = it }
            world.ledger.post("sdd-debit-$collectionId", entry!!)
            val record = SddCollectionRecord(
                id = collectionId,
                mandateId = mandate.id,
                amount = instruction.amount,
                currency = instruction.currency,
                status = SddCollectionStatus.SETTLED,
                debitJournalId = journalId,
            )
            world.sdd.recordCollection(record)
            world.sdd.replaceMandate(MandateLifecycle.recordCollection(mandate, today))
            world.audit.append(
                "sdd collection $collectionId SETTLED: ${instruction.amount} EUR (mandate ${mandate.id})",
            )
            record
        } catch (e: SimulatedWriteFailure) {
            record(world, mandate, instruction.amount, SddCollectionStatus.FAILED, TECHNICAL_ERROR)
            world.audit.append("sdd collection $collectionId FAILED: ${e.message}")
            return
        }

        if (world.context.faults.shouldDuplicateEvent()) {
            // at-least-once redelivery: the same deterministic key collapses onto the one journal
            world.ledger.post("sdd-debit-$collectionId", entry)
            world.audit.append("sdd collection $collectionId: duplicate debit delivery collapsed")
        }
        if (world.context.faults.shouldConflict()) {
            world.ledger.reverse(
                settled.debitJournalId!!,
                random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { random.nextUuid() },
            )
            world.sdd.replaceCollection(settled.copy(status = SddCollectionStatus.REVERSED, reversed = true))
            world.audit.append("sdd collection $collectionId REVERSED: post-commit conflict")
            return
        }
        if (random.chance(REFUND_CLAIM_RATE)) {
            postRefundLeg(world, mandate, settled, today)
        }
    }

    private fun postRefundLeg(world: World, mandate: SddMandate, record: SddCollectionRecord, today: LocalDate) {
        val random = world.context.random
        val claimDate = today.plusDays(random.nextLong(0L, MAX_CLAIM_OFFSET_DAYS + 1L))
        val decision = RefundPolicy.assess(mandate.scheme, today, claimDate, authorised = true)
        if (decision !is RefundDecision.Eligible) return
        try {
            if (world.context.faults.shouldFailWrite()) {
                throw SimulatedWriteFailure("sdd refund post failed for collection ${record.id}")
            }
            val journalId = random.nextUuid()
            val entry = buildEurEntry(
                world,
                journalId,
                record.id,
                description = "sdd refund ${mandate.umr}",
                amount = record.amount,
                debtorAccount = mandate.accountId,
                refund = true,
            )
            world.ledger.post("sdd-refund-${record.id}", entry)
            world.sdd.replaceCollection(record.copy(refundJournalId = journalId))
            world.audit.append("sdd collection ${record.id} REFUNDED: ${record.amount} EUR (${decision.reasonCode})")
        } catch (e: SimulatedWriteFailure) {
            // The claim never registered: the book must not owe a refund it didn't pay.
            world.audit.append("sdd refund claim for ${record.id} abandoned: ${e.message}")
        }
    }

    private fun record(
        world: World,
        mandate: SddMandate,
        amount: BigDecimal,
        status: SddCollectionStatus,
        reasonCode: String?,
    ): SddCollectionRecord {
        val record = SddCollectionRecord(
            id = world.context.random.nextUuid(),
            mandateId = mandate.id,
            amount = amount,
            currency = SCHEME_CURRENCY,
            status = status,
            reasonCode = reasonCode,
        )
        world.sdd.recordCollection(record)
        world.audit.append("sdd collection ${record.id} $status ($reasonCode): $amount EUR")
        return record
    }

    /**
     * The EUR money movement: SEPA Direct Debit is EUR-only, so both legs are EUR on the
     * scheme-settlement GL with the debtor carried as the customer dimension of the funds leg
     * (the customer's CZK balance projection never sees an EUR account, exactly like
     * [SctInstPaymentScenario]). [refund] swaps the legs — the exact reverse of the debit.
     */
    private fun buildEurEntry(
        world: World,
        journalId: UUID,
        transactionId: UUID,
        description: String,
        amount: BigDecimal,
        debtorAccount: UUID,
        refund: Boolean,
    ): JournalEntry {
        val money = Money.of(amount, SCHEME_CURRENCY)
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        val debit = JournalLine(
            id = UUID(journalId.mostSignificantBits, 1L),
            journalId = journalId,
            glAccountId = SDD_SETTLEMENT_GL,
            side = if (refund) JournalSide.CREDIT else JournalSide.DEBIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 1,
            subAccountId = debtorAccount,
        )
        val credit = JournalLine(
            id = UUID(journalId.mostSignificantBits, 2L),
            journalId = journalId,
            glAccountId = SDD_SETTLEMENT_GL,
            side = if (refund) JournalSide.DEBIT else JournalSide.CREDIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 2,
            subAccountId = SCHEME_HOUSE,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = transactionId,
            entryDate = today,
            valueDate = today,
            description = description,
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    private fun iban(accountId: UUID): String = "DE0000000000$accountId".take(IBAN_PREFIX_LENGTH)
}
