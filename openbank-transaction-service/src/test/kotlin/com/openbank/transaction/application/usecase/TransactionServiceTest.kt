// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.usecase

import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.domain.money.Money
import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.temporal.TemporalConfig
import com.openbank.transaction.application.port.`in`.InitiateTransactionCommand
import com.openbank.transaction.application.port.`in`.ListTransactionsQuery
import com.openbank.transaction.application.port.`in`.ReverseTransactionCommand
import com.openbank.transaction.application.port.out.FxRatePort
import com.openbank.transaction.application.port.out.FxRateView
import com.openbank.transaction.application.port.out.TransactionEventPublisher
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.application.workflow.PaymentWorkflow
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.domain.saga.SagaState
import com.openbank.transaction.domain.settlement.SettlementDateResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class TransactionServiceTest {
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var eventPublisher: TransactionEventPublisher
    private lateinit var fxRatePort: FxRatePort
    private lateinit var temporalConfig: TemporalConfig
    private lateinit var workflowClient: WorkflowClient
    private lateinit var workflowStub: PaymentWorkflow

    private lateinit var service: TransactionService

    /**
     * FIXED, and that is the whole point. Six tests below build a requested value date as
     * `today.plusMonths(1)`, and [SettlementDateResolver] rolls a requested date to a business day
     * (FOLLOWING). Under a system clock this file is a calendar time-bomb in two directions:
     *
     *  - the two tests that assert the requested date survives verbatim fail whenever today + 1
     *    month lands on a weekend or holiday — on 2026-08-19 that was Saturday 2026-09-19, and
     *    `a SEPA settlement booked as TRANSFER still honours the requested value date` and
     *    `a domestic payment leaving the bank still honours the resolver` both went red;
     *  - the four that assert the same-day branch (`isEqualTo(today)`) are exposed whenever
     *    *today itself* is not a business day, i.e. every weekend run.
     *
     * Nothing here was wrong on the day it was written; the test simply asked the calendar a
     * different question each morning. Path-scoped CI hid it — the module builds only when
     * something touches it, so the failure surfaced on an unrelated docs-only PR.
     *
     * 2026-01-06 is a Tuesday, 09:00Z = 10:00 Europe/Prague (before the 16:00 cut-off), and
     * 2026-02-06 is a Friday. Both ends of every `plusMonths(1)` below are business days by
     * construction, on every future run.
     */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-06T09:00:00Z"), ZoneOffset.UTC)
    private var lastSaved: Transaction? = null

    @BeforeEach
    fun setUp() {
        transactionRepository = mockk()
        eventPublisher = mockk()
        fxRatePort = mockk()
        temporalConfig = mockk()
        workflowClient = mockk()
        workflowStub = mockk()
        every { temporalConfig.taskQueue() } returns "openbank-payment-execution"
        every {
            workflowClient.newWorkflowStub(PaymentWorkflow::class.java, any<WorkflowOptions>())
        } returns workflowStub
        // The clock is passed EXPLICITLY: the @Inject constructor defaults to Clock.systemUTC(),
        // so a fixed clock held only by the test would describe a different day from the one the
        // service resolves settlement dates against — expectations and subject must share it.
        service = TransactionService(
            transactionRepository,
            eventPublisher,
            fxRatePort,
            temporalConfig,
            workflowClient,
            clock,
        )
    }

    @Test
    fun `initiate transaction replays existing record for idempotency key`(): Unit = runBlocking {
        val command = initiateCommand()
        val existing = transaction(idempotencyKey = command.idempotencyKey)

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns existing

        val result = service.initiateTransaction(command)

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { transactionRepository.save(any(), any()) }
        verify(exactly = 0) { workflowClient.newWorkflowStub(any<Class<*>>(), any<WorkflowOptions>()) }
    }

    @Test
    fun `initiate transaction reports the terminal state the workflow committed, writing none itself`(): Unit =
        runBlocking {
            // #4238: the terminal status + completed outbox message are written by the workflow's
            // markCompleted activity (PaymentActivitiesImpl), not here. This test used to assert the
            // opposite — that the service itself called update(COMPLETED, completed-event) once
            // stub.execute() returned — which is exactly the non-durable write the issue is about.
            val command = initiateCommand()

            coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
            every { eventPublisher.initiatedPayload(any()) } returns "{\"event\":\"initiated\"}"
            stubWorkflowCommitted(TransactionStatus.COMPLETED)
            every { workflowStub.execute(any()) } returns SagaState.COMPLETED

            val result = service.initiateTransaction(command)

            assertThat(result.status).isEqualTo(TransactionStatus.COMPLETED)
            assertThat(result.completedAt).isNotNull()
            assertThat(result.referenceNumber).startsWith("TXN")
            assertThat(result.amount).isEqualTo(Money.of(command.amount, command.currencyCode))

            coVerify {
                transactionRepository.save(
                    match {
                        it.idempotencyKey == command.idempotencyKey &&
                            it.type == command.type &&
                            it.status == TransactionStatus.PENDING
                    },
                    match<OutboxMessage> {
                        it.eventType == "openbank.transactions.transaction.initiated" &&
                            it.aggregateId == result.id &&
                            it.payload.contains("initiated")
                    },
                )
                // The state comes from the row the workflow wrote — one read, no second write.
                transactionRepository.findById(result.id)
            }
            coVerify(exactly = 0) { transactionRepository.update(any(), any()) }
            verify(exactly = 1) { workflowStub.execute(result.id) }
        }

    @Test
    fun `initiate transaction drives through Temporal payment workflow`(): Unit = runBlocking {
        val command = initiateCommand()
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)

        val result = service.initiateTransaction(command)

        assertThat(result.status).isEqualTo(TransactionStatus.COMPLETED)
        verify(exactly = 1) { workflowStub.execute(result.id) }
    }

    /**
     * A savings-to-checking pocket move, reported directly: it looked like it went through, then
     * reverted a few seconds later. Root cause — TRANSFER (own-account) was routed through
     * SettlementDateResolver's cutoff/business-day rules, meant for a payment that leaves the bank
     * on an external rail. A transfer submitted after the 16:00 cutoff (or on a Friday evening at
     * all) got value-dated on the next business day; the optimistic UI showed the money moved, but
     * balance-service's effectiveAvailable() correctly would not count it as spendable until then,
     * so the very next transfer attempting to move it back out failed 422 insufficient-funds and the
     * UI reverted. TRANSFER must book same-day, always — it never touches a clearing calendar.
     */
    @Test
    fun `own-account transfer books and values same-day, ignoring any requested date and the cutoff`(): Unit =
        runBlocking {
            // Same zone the production code reads "today" in (ADR-0207 D1) — a UTC "today" would be
            // flaky near midnight Prague time, exactly the class of bug that zone owns.
            val today = Instant.now(clock).atZone(SettlementDateResolver.BANK_ZONE).toLocalDate()
            // A date far in the future is deliberately requested — proving TRANSFER does not defer
            // to it, unlike every other payment type (see the settlement-date resolver tests).
            val command = initiateCommand().copy(valueDate = today.plusMonths(1))

            coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
            every { eventPublisher.initiatedPayload(any()) } returns "{}"
            stubWorkflowCommitted(TransactionStatus.COMPLETED)
            every { workflowStub.execute(any()) } returns SagaState.COMPLETED

            val result = service.initiateTransaction(command)

            assertThat(result.valueDate).isEqualTo(today)
            assertThat(result.bookingDate).isEqualTo(today)
        }

    /**
     * The other half of the rule above, and the reason the discriminator is not the type alone.
     *
     * `openbank-sepa-payment` books its settlement leg as **type=TRANSFER with rail=SEPA_CT** — the
     * other three rails (domestic-payment, sepa-instant, swift) book DEBIT, so SEPA is the odd one
     * out. Keyed on the type alone, the own-account same-day branch would swallow every SEPA credit
     * transfer and bypass precisely the cutoff and business-day rules that exist because that money
     * really does leave the bank on an external rail with a clearing calendar.
     *
     * A requested value date one month out must therefore still be honoured here — the assertion is
     * that this transaction did NOT take the same-day branch.
     */
    @Test
    fun `a SEPA settlement booked as TRANSFER still honours the requested value date`(): Unit = runBlocking {
        val today = Instant.now(clock).atZone(SettlementDateResolver.BANK_ZONE).toLocalDate()
        val requested = today.plusMonths(1)
        // sepa-payment's real settlement request carries no targetAccountId (it never resolves an
        // internal payee) — the fixture must match that, or this test cannot tell "external SEPA"
        // apart from "internal transfer that happens to be tagged SEPA_CT".
        val command = initiateCommand().copy(valueDate = requested, rail = PaymentRail.SEPA_CT, targetAccountId = null)

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.initiateTransaction(command)

        assertThat(result.valueDate)
            .describedAs("a railed TRANSFER must keep going through SettlementDateResolver")
            .isNotEqualTo(today)
        assertThat(result.valueDate).isEqualTo(requested)
    }

    /**
     * The original guard (`type == TRANSFER && rail == null`) covered an own-account transfer but
     * missed everything else that also never leaves the bank. domestic-payment books its in-house
     * settlement leg as **type=DEBIT, rail=DOMESTIC** — the rail is real (a scheme exists), but a
     * `targetAccountId` is set because the payee is one of our own accounts, per its own comment:
     * "External transfers keep a null target (the money genuinely leaves the bank)."
     *
     * Verified live in the sandbox ledger before this fix: an in-house "Interní převod" debit at
     * 21:11 on a Thursday booked value_date the next day, and one made on a Saturday booked two
     * days out — on a CERTIS calendar the money never touched.
     */
    @Test
    fun `an in-house domestic payment with a resolved payee books same-day despite rail=DOMESTIC`(): Unit =
        runBlocking {
            val today = Instant.now(clock).atZone(SettlementDateResolver.BANK_ZONE).toLocalDate()
            val command = initiateCommand().copy(
                type = TransactionType.DEBIT,
                rail = PaymentRail.DOMESTIC,
                valueDate = today.plusMonths(1),
            )

            coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
            every { eventPublisher.initiatedPayload(any()) } returns "{}"
            stubWorkflowCommitted(TransactionStatus.COMPLETED)
            every { workflowStub.execute(any()) } returns SagaState.COMPLETED

            val result = service.initiateTransaction(command)

            assertThat(result.valueDate).isEqualTo(today)
            assertThat(result.bookingDate).isEqualTo(today)
        }

    /**
     * The counterpart: a domestic DEBIT with no resolved payee (the ordinary case — money genuinely
     * leaving the bank) must keep going through the resolver. Proves the discriminator is the payee
     * leg, not the transaction type.
     */
    @Test
    fun `a domestic payment leaving the bank still honours the resolver`(): Unit = runBlocking {
        val today = Instant.now(clock).atZone(SettlementDateResolver.BANK_ZONE).toLocalDate()
        val requested = today.plusMonths(1)
        val command = initiateCommand().copy(
            type = TransactionType.DEBIT,
            rail = PaymentRail.DOMESTIC,
            targetAccountId = null,
            valueDate = requested,
        )

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.initiateTransaction(command)

        assertThat(result.valueDate)
            .describedAs("a domestic payment with no internal payee must keep going through the resolver")
            .isNotEqualTo(today)
        assertThat(result.valueDate).isEqualTo(requested)
    }

    /**
     * The welcome bonus (account-service, `type=CREDIT`, no rail) and a reversal credit both hit
     * this same shape: a payee of ours, no rail. Verified live — a bonus granted 08:31 on a Saturday
     * booked on the Monday.
     */
    @Test
    fun `a welcome-bonus-shaped credit with no rail books same-day`(): Unit = runBlocking {
        val today = Instant.now(clock).atZone(SettlementDateResolver.BANK_ZONE).toLocalDate()
        val command = initiateCommand().copy(
            type = TransactionType.CREDIT,
            sourceAccountId = null,
            rail = null,
            valueDate = today.plusMonths(1),
        )

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.initiateTransaction(command)

        assertThat(result.valueDate).isEqualTo(today)
        assertThat(result.bookingDate).isEqualTo(today)
    }

    /**
     * A rail honestly stamped INTERNAL, FEE or INTEREST must never roll, whatever the type — this is
     * the guard against the exact regression the audit warned about: the old check was keyed on
     * `rail == null`, so stamping rail correctly on an own-account move would have silently
     * re-introduced the bug it fixed.
     */
    @Test
    fun `a rail honestly stamped INTERNAL never rolls`(): Unit = runBlocking {
        val today = Instant.now(clock).atZone(SettlementDateResolver.BANK_ZONE).toLocalDate()
        val command = initiateCommand().copy(rail = PaymentRail.INTERNAL, valueDate = today.plusMonths(1))

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.initiateTransaction(command)

        assertThat(result.valueDate).isEqualTo(today)
        assertThat(result.bookingDate).isEqualTo(today)
    }

    @Test
    fun `initiate cross-currency transaction via fx-service carries the applied rate`(): Unit = runBlocking {
        val command = initiateCommand().copy(
            amount = BigDecimal("40.00"),
            currencyCode = "EUR",
            settlementCurrencyCode = "CZK",
        )

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery {
            fxRatePort.getRate("EUR", "CZK")
        } returns FxRateView("EUR", "CZK", BigDecimal("24.50"), BigDecimal("25.00"))
        every { eventPublisher.initiatedPayload(any()) } returns "{\"event\":\"initiated\"}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.initiateTransaction(command)

        assertThat(result.amount).isEqualTo(Money.of(BigDecimal("40.00"), "EUR"))
        assertThat(result.fxRate).isEqualByComparingTo("25.00")
        assertThat(result.baseAmount).isEqualTo(Money.of(BigDecimal("1000.00"), "CZK"))
        coVerify { fxRatePort.getRate("EUR", "CZK") }
    }

    @Test
    fun `sell-specified cross-currency uses settlement amount verbatim (ADR-0107)`(): Unit = runBlocking {
        // Pocket sweep: sell exactly 40.00 EUR into CZK. The caller fixes the sell amount so the
        // source pocket debits to zero; the rate is implied (settlement / payment), no fx lookup.
        val command = initiateCommand().copy(
            amount = BigDecimal("1000.00"),
            currencyCode = "CZK",
            settlementCurrencyCode = "EUR",
            settlementAmount = BigDecimal("40.00"),
        )

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.initiateTransaction(command)

        // baseAmount (the sell leg) is exactly what the caller asked — not amount x rate.
        assertThat(result.baseAmount).isEqualTo(Money.of(BigDecimal("40.00"), "EUR"))
        assertThat(result.fxRate).isEqualByComparingTo("0.04") // 40.00 / 1000.00, implied
        coVerify(exactly = 0) { fxRatePort.getRate(any(), any()) }
    }

    @Test
    fun `initiate same-currency transaction does not call fx-service`(): Unit = runBlocking {
        val command = initiateCommand()

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.initiateTransaction(command)

        assertThat(result.fxRate).isNull()
        assertThat(result.baseAmount).isEqualTo(result.amount)
        coVerify(exactly = 0) { fxRatePort.getRate(any(), any()) }
    }

    @Test
    fun `initiate transaction reports FAILED from the workflow without writing it here`(): Unit = runBlocking {
        // Mirror of the completed case: the failed status + failed event are the workflow's
        // markFailed activity, so the service only reports what was committed (#4238).
        val command = initiateCommand()

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{\"event\":\"initiated\"}"
        stubWorkflowCommitted(TransactionStatus.FAILED)
        every { workflowStub.execute(any()) } returns SagaState.FAILED

        val result = service.initiateTransaction(command)

        assertThat(result.status).isEqualTo(TransactionStatus.FAILED)
        assertThat(result.failedAt).isNotNull()
        assertThat(result.failureReason).contains("FAILED")

        coVerify(exactly = 0) { transactionRepository.update(any(), any()) }
    }

    @Test
    fun `list transactions decodes cursor and emits next cursor from final returned record`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val afterId = UUID.randomUUID()
        val transactions = listOf(
            transaction(sourceAccountId = accountId),
            transaction(sourceAccountId = accountId),
            transaction(sourceAccountId = accountId),
        )

        coEvery { transactionRepository.findByAccountId(accountId, 3, afterId) } returns transactions

        val page = service.listTransactions(
            ListTransactionsQuery(
                accountId = accountId,
                limit = 2,
                afterCursor = CursorEncoder.encode(afterId.toString()),
            ),
        )

        assertThat(page.data).containsExactly(transactions[0], transactions[1])
        assertThat(page.pagination.hasNextPage).isTrue()
        assertThat(CursorEncoder.decode(page.pagination.nextCursor!!)).isEqualTo(transactions[1].id.toString())
    }

    @Test
    fun `reverseTransaction creates REVERSAL transaction and marks original as REVERSED`(): Unit = runBlocking {
        val originalSourceId = UUID.randomUUID()
        val original = transaction(idempotencyKey = "orig-idem", sourceAccountId = originalSourceId)
            .copy(status = TransactionStatus.COMPLETED, completedAt = Instant.now())
        val command = ReverseTransactionCommand(
            originalTransactionId = original.id,
            idempotencyKey = "rev-idem-1",
            reason = "Customer requested return",
        )

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery {
            transactionRepository.update(match { it.status == TransactionStatus.REVERSED })
        } answers { firstArg() }
        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returnsMany listOf(null, null)
        every { eventPublisher.initiatedPayload(any()) } returns "{}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        // The original is read by id too; the reversal credit's own reload comes from the stub above.
        coEvery { transactionRepository.findById(original.id) } returns original
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.reverseTransaction(command)

        assertThat(result.type).isEqualTo(TransactionType.REVERSAL)
        assertThat(result.targetAccountId).isEqualTo(originalSourceId)
        assertThat(result.sourceAccountId).isNull()
        coVerify { transactionRepository.update(match { it.status == TransactionStatus.REVERSED }) }
    }

    @Test
    fun `reverseTransaction is idempotent for same idempotency key`(): Unit = runBlocking {
        val existing = transaction(idempotencyKey = "rev-idem-2").copy(
            type = TransactionType.REVERSAL,
            status = TransactionStatus.COMPLETED,
            completedAt = Instant.now(),
        )
        val command = ReverseTransactionCommand(
            originalTransactionId = UUID.randomUUID(),
            idempotencyKey = "rev-idem-2",
            reason = "duplicate",
        )

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns existing

        val result = service.reverseTransaction(command)

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { transactionRepository.findById(any()) }
    }

    @Test
    fun `reverseTransaction fails when original transaction is not COMPLETED`(): Unit = runBlocking {
        val original = transaction(idempotencyKey = "orig-pending").copy(status = TransactionStatus.PENDING)
        val command = ReverseTransactionCommand(
            originalTransactionId = original.id,
            idempotencyKey = "rev-idem-3",
            reason = "bad state",
        )

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { transactionRepository.findById(original.id) } returns original

        val exception = runCatching { service.reverseTransaction(command) }.exceptionOrNull()

        assertThat(exception).isInstanceOf(IllegalStateException::class.java)
        assertThat(exception!!.message).contains("PENDING")
    }

    @Test
    fun `rail and instructionType from command are stamped on the saved transaction`(): Unit = runBlocking {
        val command = initiateCommand().copy(
            rail = PaymentRail.SEPA_CT,
            instructionType = InstructionType.ONE_OFF,
        )

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.initiateTransaction(command)

        assertThat(result.rail).isEqualTo(PaymentRail.SEPA_CT)
        assertThat(result.instructionType).isEqualTo(InstructionType.ONE_OFF)

        coVerify {
            transactionRepository.save(
                match {
                    it.rail == PaymentRail.SEPA_CT &&
                        it.instructionType == InstructionType.ONE_OFF
                },
                any(),
            )
        }
    }

    @Test
    fun `initiate transaction normalizes a wide-scale amount to currency minor units`(): Unit = runBlocking {
        // A rail may persist and forward an over-scaled decimal (e.g. 123.000000); the booking
        // ingest must round to the currency's minor units instead of rejecting with 400 — this is
        // the single generic guard for all callers (domestic, SEPA, SEPA-instant, welcome-bonus).
        val command = initiateCommand().copy(amount = BigDecimal("123.000000"), currencyCode = "CZK")

        coEvery { transactionRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        every { eventPublisher.initiatedPayload(any()) } returns "{\"event\":\"initiated\"}"
        stubWorkflowCommitted(TransactionStatus.COMPLETED)
        every { workflowStub.execute(any()) } returns SagaState.COMPLETED

        val result = service.initiateTransaction(command)

        // Booked at scale 2 (CZK minor units), value preserved — no exception thrown.
        assertThat(result.amount).isEqualTo(Money.of(BigDecimal("123.00"), "CZK"))
        assertThat(result.amount.amount.scale()).isEqualTo(2)
    }

    /**
     * Stands in for the workflow's terminal-write activity (#4238): `save` records the PENDING row,
     * and the re-read the service does after `stub.execute()` returns hands back the row in the
     * state the workflow committed. Any test that stubs this and still sees `update(...)` called by
     * the service has caught the ownership regressing.
     */
    private fun stubWorkflowCommitted(terminalStatus: TransactionStatus) {
        coEvery { transactionRepository.save(any(), any()) } answers {
            firstArg<Transaction>().also { lastSaved = it }
        }
        coEvery { transactionRepository.findById(any()) } answers {
            val saved = lastSaved ?: return@answers null
            when (terminalStatus) {
                TransactionStatus.COMPLETED -> saved.complete(clock)
                TransactionStatus.FAILED -> saved.fail("Payment workflow did not complete (state=FAILED)", clock)
                else -> saved
            }
        }
    }

    private fun initiateCommand() = InitiateTransactionCommand(
        idempotencyKey = "txn-idem-1",
        type = TransactionType.TRANSFER,
        sourceAccountId = UUID.randomUUID(),
        targetAccountId = UUID.randomUUID(),
        amount = BigDecimal("1250.50"),
        currencyCode = "CZK",
        description = "Invoice settlement",
        valueDate = LocalDate.of(2026, 6, 1),
        initiatedBy = UUID.randomUUID(),
    )

    private fun transaction(
        idempotencyKey: String = "existing-idem",
        sourceAccountId: UUID? = UUID.randomUUID(),
    ): Transaction = Transaction(
        id = UUID.randomUUID(),
        referenceNumber = "TXN202606010001",
        type = TransactionType.TRANSFER,
        sourceAccountId = sourceAccountId,
        targetAccountId = UUID.randomUUID(),
        amount = Money.of(BigDecimal("1250.50"), "CZK"),
        fxRate = null,
        baseAmount = Money.of(BigDecimal("1250.50"), "CZK"),
        status = TransactionStatus.PENDING,
        description = "Invoice settlement",
        valueDate = LocalDate.of(2026, 6, 1),
        bookingDate = LocalDate.of(2026, 6, 1),
        initiatedAt = Instant.now(),
        completedAt = null,
        failedAt = null,
        failureReason = null,
        idempotencyKey = idempotencyKey,
        version = 0L,
    )
}
