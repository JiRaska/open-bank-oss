// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.model.SwiftSettlementPosting
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Money-path SWIFT adoption: exercises [SwiftMessageScenario] directly against the REAL
 * swift-service domain — the `SwiftMessage` aggregate, its real `validate()` admission and the
 * real `moneyOfMinorUnits` currency-scale factory — so the harness's binding to swift-service is
 * proven by an actual assertion, not merely by compiling, the same shape as
 * [SddCollectionScenarioTest]/[SctInstPaymentScenarioTest].
 *
 * The forced-fault branches are deterministic (`FaultProfile(1.0)` fires its draw with certainty,
 * and the write-failure draw precedes the conflict draw); the seeded branches (scheme reject,
 * operator ack) are located by scanning fixed seeds, so the test stays green if the scenario's
 * draw order changes.
 */
class SwiftMessageScenarioTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 16)

    private fun newWorld(seed: Long, faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed, faultProfile), SimulationConfig())

    private fun newMessage(
        senderBic: String = "DEUTDEFF",
        receiverBic: String = "CHASUS33",
        transactionReference: String = "trn-1",
        amount: BigDecimal = BigDecimal("100.00"),
        currency: String = "EUR",
        chargeCode: String = "OUR",
        valueDate: String = today.format(DateTimeFormatter.BASIC_ISO_DATE),
    ) = SwiftMessage(
        id = UUID.randomUUID(),
        idempotencyKey = "swift-test-${UUID.randomUUID()}",
        messageType = SwiftMessageType.MT103,
        senderBic = senderBic,
        receiverBic = receiverBic,
        transactionReference = transactionReference,
        relatedReference = null,
        valueDate = valueDate,
        amount = SwiftMessage.moneyOfMinorUnits(amount.unscaledValue().longValueExact(), currency),
        orderingCustomerAccount = "CZ6508000000192000145399",
        orderingCustomerAccountId = UUID.randomUUID(),
        orderingCustomerName = "Ordering Customer",
        beneficiaryAccount = "DE0000000000000000000000",
        beneficiaryName = "Beneficiary",
        remittanceInfo = null,
        chargeCode = chargeCode,
        priority = SwiftPriority.NORMAL,
        status = SwiftStatus.PENDING,
        rawMt = null,
        ackReceivedAt = null,
        rejectionReason = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `the real validate admits a well-formed message and rejects each broken field`() {
        assertThat(newMessage().validate()).isEmpty()

        assertThat(newMessage(senderBic = "SHORT").validate()).anyMatch { it.contains("sender BIC") }
        assertThat(newMessage(receiverBic = "TOO-LONG-BIC-HERE").validate())
            .anyMatch { it.contains("receiver BIC") }
        assertThat(newMessage(transactionReference = " ").validate())
            .anyMatch { it.contains("Transaction reference") }
        assertThat(newMessage(amount = BigDecimal.ZERO).validate()).anyMatch { it.contains("positive") }
        assertThat(newMessage(chargeCode = "XXX").validate()).anyMatch { it.contains("charge code") }
        assertThat(newMessage(valueDate = "2026-10-16").validate()).anyMatch { it.contains("valueDate") }
    }

    @Test
    fun `the real moneyOfMinorUnits factory holds the currency minor-unit scale both ways`() {
        val eur = SwiftMessage.moneyOfMinorUnits(12_345L, "EUR")
        assertThat(eur.amount).isEqualByComparingTo(BigDecimal("123.45"))
        assertThat(eur.currency).isEqualTo(CurrencyCode.EUR)

        val jpy = SwiftMessage.moneyOfMinorUnits(12_345L, "JPY")
        assertThat(jpy.amount).isEqualByComparingTo(BigDecimal("12345"))
        assertThat(jpy.currency).isEqualTo(CurrencyCode.of("JPY"))

        // the aggregate's own read-back is exact at the currency's scale
        val message = newMessage()
        assertThat(message.amountMinorUnits).isEqualTo(message.amount.amount.unscaledValue().longValueExact())
    }

    @Test
    fun `a happy-path step completes the message and posts one live settlement journal`() {
        val world = worldWithStatus(SwiftStatus.COMPLETED)
        val message = world.swift.messages.first { it.status == SwiftStatus.COMPLETED }
        val posting = world.swift.postingOf(message.id)
        assertThat(posting).isNotNull()
        assertThat(posting!!.reversed).isFalse()
        assertThat(posting.amount.compareTo(message.amount.amount)).isEqualTo(0)
        assertThat(posting.currency).isEqualTo(message.currency)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.swiftCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.swiftMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a scheme reject moves no money and leaves no settlement posting`() {
        val world = worldWithStatus(SwiftStatus.REJECTED)
        val rejected = world.swift.messages.first { it.status == SwiftStatus.REJECTED }
        assertThat(world.swift.postingOf(rejected.id)).isNull()
        assertThat(rejected.rejectionReason).isNotNull()
        assertThat(MoneyPathInvariants.swiftCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.swiftMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a write fault at the settlement post rejects the message before any money moved`() {
        // 100% write-failure rate forces the settlement fault on every non-rejected message.
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(writeFailureRate = 1.0))
        repeat(SEED_STEPS) { SwiftMessageScenario.step(world) }

        assertThat(world.swift.messages).isNotEmpty()
        assertThat(world.ledger.postedCount()).isEqualTo(0)
        assertThat(world.swift.messages.filter { it.status == SwiftStatus.REJECTED })
            .allMatch { world.swift.postingOf(it.id) == null }
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.swiftCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.swiftMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict reverses the durable settlement journal and fails the message`() {
        // 100% lock-conflict rate forces the reversal after every settlement booked, so every
        // settled message ends FAILED with exactly two durable entries (original + reversal).
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(lockConflictRate = 1.0))
        repeat(SEED_STEPS) { SwiftMessageScenario.step(world) }

        val failed = world.swift.messages.filter { it.status == SwiftStatus.FAILED }
        assertThat(failed).isNotEmpty()
        assertThat(failed).allMatch { world.swift.postingOf(it.id)?.reversed == true }
        assertThat(world.ledger.postedCount()).isEqualTo(failed.size * 2)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.swiftCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.swiftMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `an operator acknowledgement stamps the completed message`() {
        val world = worldWithStatus(SwiftStatus.ACKNOWLEDGED)
        val acked = world.swift.messages.first { it.status == SwiftStatus.ACKNOWLEDGED }
        assertThat(acked.ackReceivedAt).isNotNull()
        // the settlement journal stays live across the operator transition
        assertThat(world.swift.postingOf(acked.id)?.reversed).isFalse()
        assertThat(MoneyPathInvariants.swiftCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.swiftMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `the money conservation invariant catches a journal on a rejected message`() {
        val world = worldWithStatus(SwiftStatus.REJECTED)
        val rejected = world.swift.messages.first { it.status == SwiftStatus.REJECTED }
        // mutation: a rejected message must never have moved money — give it a live journal
        world.swift.recordPosting(
            SwiftSettlementPosting(
                messageId = rejected.id,
                journalId = UUID.randomUUID(),
                amount = rejected.amount.amount,
                currency = rejected.currency,
            ),
        )
        assertThat(MoneyPathInvariants.swiftMoneyConservation.check(world)).isNotNull()
    }

    /** Scan fixed seeds until a message reaches [status], so the test survives draw-order changes. */
    private fun worldWithStatus(status: SwiftStatus): World {
        for (seed in 0L..300L) {
            val world = newWorld(seed)
            repeat(SEED_STEPS) { SwiftMessageScenario.step(world) }
            if (world.swift.messages.any { it.status == status }) return world
        }
        error("no seed in 0..300 produced a $status swift message")
    }

    companion object {
        private const val SEED_STEPS = 10
    }
}
