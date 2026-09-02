// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.usecase

import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.domain.payment.SettlementScope
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.temporal.TemporalConfig
import com.openbank.transaction.application.port.`in`.GetTransactionQuery
import com.openbank.transaction.application.port.`in`.InitiateTransactionCommand
import com.openbank.transaction.application.port.`in`.ListTransactionsQuery
import com.openbank.transaction.application.port.`in`.ReverseTransactionCommand
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.application.port.out.FxRatePort
import com.openbank.transaction.application.port.out.TransactionEventPublisher
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.application.workflow.PaymentWorkflow
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.domain.saga.SagaState
import com.openbank.transaction.domain.settlement.SettlementDateResolver
import com.openbank.transaction.domain.settlement.SettlementDates
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.vertx.pgclient.PgException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.PersistenceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val eventPublisher: TransactionEventPublisher,
    private val fxRatePort: FxRatePort,
    private val temporalConfig: TemporalConfig,
    private val workflowClient: WorkflowClient,
    private val clock: Clock,
) : TransactionUseCase {

    /**
     * Field injection keeps the public constructor used by existing unit tests stable while
     * making the trace provider replaceable in the assertion-backed integration contract.
     */
    @Inject
    lateinit var tracer: Tracer

    @Inject
    constructor(
        transactionRepository: TransactionRepository,
        eventPublisher: TransactionEventPublisher,
        fxRatePort: FxRatePort,
        temporalConfig: TemporalConfig,
        workflowClient: WorkflowClient,
    ) : this(
        transactionRepository,
        eventPublisher,
        fxRatePort,
        temporalConfig,
        workflowClient,
        Clock.systemUTC(),
    )

    companion object {
        private const val TRANSACTION_INITIATED_EVENT = "openbank.transactions.transaction.initiated"
        // The completed/failed event types moved with the terminal write into
        // PaymentActivitiesImpl (#4238) — they are emitted by the workflow, not by this caller.

        // Scale for the implied FX rate on a sell-specified conversion (ADR-0107): rate is
        // derived as settlement/payment and only carried for the ledger's FX posting.
        private const val IMPLIED_FX_RATE_SCALE = 8
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun initiateTransaction(command: InitiateTransactionCommand): Transaction {
        val span = activeTracer().spanBuilder("transaction.initiate")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan()
        try {
            return initiateTransactionInternal(command).also {
                // A controlled vocabulary: neither amount, account, party, description nor idempotency key.
                span.setAttribute("openbank.transaction.status", it.status.name)
            }
        } catch (error: Exception) {
            span.recordException(error)
            throw error
        } finally {
            span.end()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun activeTracer(): Tracer =
        if (this::tracer.isInitialized) tracer else GlobalOpenTelemetry.getTracer("openbank-transaction-service")

    // Existing orchestration stays deliberately contiguous; extracting it only to host the
    // span boundary must not alter payment/Temporal sequencing.
    @Suppress("LongMethod")
    private suspend fun initiateTransactionInternal(command: InitiateTransactionCommand): Transaction {
        val existing = transactionRepository.findByIdempotencyKey(command.idempotencyKey)
        if (existing != null) return existing

        val currency = CurrencyCode.of(command.currencyCode)
        // Normalize the principal to the currency's minor units at the booking ingest (ADR-0108).
        // A rail may hand us a wide-scale decimal (e.g. 123.000000 persisted by domestic/SEPA) that
        // Money's strict scale invariant would reject with 400, stranding settlement. Rounding here
        // — the single chokepoint every REST and Kafka booking flows through — makes the API robust
        // to ANY caller (domestic, SEPA, SEPA-instant, welcome-bonus, future rails) instead of each
        // sender having to setScale() itself. The FX/settlement leg in resolveSettlement already
        // normalizes its own amounts; Money's constructor invariant stays as a backstop.
        val normalizedAmount = command.amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_UP)
        val amount = Money.of(normalizedAmount, command.currencyCode)
        // Business-rule violation (well-formed request, value breaks an invariant) ->
        // IllegalStateException maps to 422 via openbank-libs CommonExceptionMappers, in
        // contrast to IllegalArgumentException (malformed input) which maps to 400.
        check(amount.isPositive()) { "Amount must be positive" }

        // Non-repudiation gate (ADR-0021): a CUSTOMER-initiated movement must carry either the
        // consumed, device-signed SCA challenge that authorised it, or a documented exemption
        // (PSD2 RTS Art. 15 own-account transfers). Bank/system postings (no customer party —
        // interest, fees, clearing) are out of SCA scope by definition.
        if (command.initiatedByPartyId != null) {
            require(command.scaChallengeId != null || !command.scaExemption.isNullOrBlank()) {
                "Customer-initiated transaction requires an SCA challenge or a documented exemption"
            }
        }

        val (fxRate, baseAmount) = resolveSettlement(amount, command.settlementCurrencyCode, command.settlementAmount)

        // Own-account TRANSFER (checking <-> savings, pocket moves) never goes through
        // SettlementDateResolver's cutoff/business-day rules. Those rules exist for payments that
        // leave the bank on an external rail with a real submission deadline and a clearing
        // calendar; a TRANSFER never leaves the ledger. Routing it through the same resolver meant
        // a transfer submitted after the 16:00 cutoff — or on a Friday evening at all — booked on
        // the next business day, so the money "arrived" in the app immediately (optimistic UI) but
        // was not actually spendable until Monday: balance-service's effectiveAvailable() correctly
        // excludes a not-yet-effective credit, so the reverse transfer back out then failed 422
        // insufficient-funds and the optimistic UI reverted a few seconds later — reported directly
        // as "I moved money into savings and now can't move it back, this can't work like this."
        // Same-day for both legs: an internal transfer has no clearing window to miss.
        //
        // `rail == null` is the discriminator, NOT the type alone. openbank-sepa-payment books its
        // settlement leg as type=TRANSFER with rail=SEPA_CT (the other three rails --
        // domestic-payment, sepa-instant, swift -- book DEBIT, so SEPA is the odd one out). On type
        // alone this branch would force every SEPA credit transfer to same-day and bypass exactly
        // the cutoff and business-day rules the paragraph above says it must not touch: money that
        // really does leave the bank on an external rail with a real clearing calendar. An
        // own-account move carries no rail -- customer-edge sets none on any of its three TRANSFER
        // payloads -- so the pair (TRANSFER, no rail) is what "never leaves the ledger" actually
        // means here.
        // Money that never reaches a scheme must not be dated by one. The original guard here only
        // covered (TRANSFER, no rail), which left every other in-house booking rolling against the
        // CERTIS calendar: an openbank-to-openbank domestic payment (rail=DOMESTIC, but with an
        // internal payee leg), the welcome bonus (type=CREDIT), and reversals. Verified in the
        // sandbox ledger — a bonus granted 08:31 on a Saturday booked on the Monday, and in-house
        // "Interní převod" debits made after 16:00 booked the next business day, on a clearing
        // calendar the money never touched. See [SettlementScope] for why the payee leg, and not
        // the rail, is what decides this.
        val staysInTheBank = (command.type == TransactionType.TRANSFER && command.rail == null) ||
            SettlementScope.staysInTheBank(command.rail, hasInternalPayee = command.targetAccountId != null)
        val dates = if (staysInTheBank) {
            val today = Instant.now(clock).atZone(SettlementDateResolver.BANK_ZONE).toLocalDate()
            SettlementDates(bookingDate = today, valueDate = today)
        } else {
            SettlementDateResolver.resolve(
                now = Instant.now(clock),
                paymentCurrency = amount.currency.code,
                settlementCurrency = baseAmount.currency.code,
                requestedValueDate = command.valueDate,
            )
        }

        val transaction = Transaction(
            id = UUID.randomUUID(),
            referenceNumber = generateReferenceNumber(),
            type = command.type,
            sourceAccountId = command.sourceAccountId,
            targetAccountId = command.targetAccountId,
            amount = amount,
            fxRate = fxRate,
            baseAmount = baseAmount,
            status = TransactionStatus.PENDING,
            description = command.description,
            valueDate = dates.valueDate,
            bookingDate = dates.bookingDate,
            initiatedAt = Instant.now(clock),
            completedAt = null,
            failedAt = null,
            failureReason = null,
            idempotencyKey = command.idempotencyKey,
            version = 0L,
            initiatedByPartyId = command.initiatedByPartyId,
            scaChallengeId = command.scaChallengeId,
            scaExemption = command.scaExemption,
            rail = command.rail,
            instructionType = command.instructionType,
        )

        val saved = try {
            transactionRepository.save(
                transaction = transaction,
                outboxMessage = OutboxMessage(
                    aggregateId = transaction.id,
                    eventType = TRANSACTION_INITIATED_EVENT,
                    payload = eventPublisher.initiatedPayload(transaction),
                ),
            )
        } catch (e: PersistenceException) {
            return recoverConcurrentReplay(e, command.idempotencyKey)
        } catch (e: PgException) {
            return recoverConcurrentReplay(e, command.idempotencyKey)
        }

        // ADR-0120 Phase 5: Temporal is the sole orchestrator — PaymentSagaOrchestrator removed.
        // stub.execute(...) is a blocking Temporal client call; offload the blocking wait to the IO dispatcher.
        val state: SagaState = withContext(Dispatchers.IO) {
            val stub = workflowClient.newWorkflowStub(
                PaymentWorkflow::class.java,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(temporalConfig.taskQueue())
                    .setWorkflowId("payment-${saved.id}")
                    .build(),
            )
            stub.execute(saved.id)
        }

        // #4238: the terminal status write and its outbox message are NOT done here. The workflow
        // owns them (PaymentWorkflowImpl's markCompleted/markFailed activity), so the record of a
        // settlement is as durable as the settlement itself — losing this request can no longer
        // strand a settled transaction on PENDING. By the time execute() returns, that activity has
        // committed, so re-reading the row is the whole of this method's remaining job: it reports
        // the state the workflow persisted rather than computing a second one. The DB row stays the
        // single source of truth for status.
        return transactionRepository.findById(saved.id)
            ?: error("Transaction ${saved.id} vanished while its payment workflow ran (state=$state)")
    }

    override suspend fun getTransaction(query: GetTransactionQuery): Transaction =
        transactionRepository.findById(query.transactionId)
            ?: throw TransactionNotFoundException("Transaction not found: ${query.transactionId}")

    override suspend fun reverseTransaction(command: ReverseTransactionCommand): Transaction {
        // Idempotency: return existing reversal if already processed with the same key
        val existing = transactionRepository.findByIdempotencyKey(command.idempotencyKey)
        if (existing != null) return existing

        val original = transactionRepository.findById(command.originalTransactionId)
            ?: throw IllegalArgumentException("Transaction ${command.originalTransactionId} not found")
        check(original.status == TransactionStatus.COMPLETED) {
            "Cannot reverse a ${original.status} transaction"
        }
        val targetAccountId = checkNotNull(original.sourceAccountId) {
            "Cannot reverse transaction ${original.id}: no source account"
        }

        // Mark original as reversed; no domain event — the reversal transaction carries the audit trail
        transactionRepository.update(original.reverse())

        // Initiate reversal credit — flows through the normal saga as an incoming credit
        // (sourceAccountId=null → no balance cover needed; DEBIT cash-clearing, CREDIT deposit-control)
        return initiateTransaction(
            InitiateTransactionCommand(
                idempotencyKey = command.idempotencyKey,
                type = TransactionType.REVERSAL,
                sourceAccountId = null,
                targetAccountId = targetAccountId,
                amount = original.amount.amount,
                currencyCode = original.amount.currency.code,
                description = "Reversal: ${command.reason}",
                valueDate = java.time.LocalDate.now(clock),
                initiatedBy = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            ),
        )
    }

    override suspend fun listTransactions(query: ListTransactionsQuery): CursorPage<Transaction> {
        val afterId = query.afterCursor?.let { UUID.fromString(CursorEncoder.decode(it)) }
        val transactions = transactionRepository.findByAccountId(query.accountId, query.limit + 1, afterId)
        val hasNext = transactions.size > query.limit
        val page = if (hasNext) transactions.dropLast(1) else transactions
        val nextCursor = if (hasNext) CursorEncoder.encode(page.last().id.toString()) else null
        return CursorPage(
            data = page,
            pagination = PageInfo(
                limit = query.limit,
                hasNextPage = hasNext,
                nextCursor = nextCursor,
            ),
        )
    }

    // Resolves the settlement (account/booking) leg of a payment. When the customer settles in the
    // same currency the payment is denominated in, there is no FX (identity base amount, null rate).
    // Otherwise the account is debited in the settlement currency, so we convert the payment amount
    // payment->settlement via fx-service (askRate, matching the fx-service convert convention) and
    // carry the applied rate for the ledger's cross-currency (four-legged) FX posting.
    private suspend fun resolveSettlement(
        amount: Money,
        settlementCurrencyCode: String?,
        settlementAmount: java.math.BigDecimal? = null,
    ): Pair<java.math.BigDecimal?, Money> {
        val paymentCcy = amount.currency.code
        val settlementCcy = settlementCurrencyCode?.takeIf { it.isNotBlank() } ?: paymentCcy
        if (settlementCcy == paymentCcy) return null to amount

        val settlement = CurrencyCode.of(settlementCcy)

        // Sell-specified (ADR-0107 pocket sweep): the caller fixes the settlement (sell)
        // amount — the full source-pocket balance — so the debit zeroes the pocket exactly.
        // The applied rate is implied (settlement / payment); the bank keeps the spread in
        // the FX-position GL, as on the derived path.
        settlementAmount?.takeIf { it.signum() > 0 }?.let { sell ->
            val base = sell.setScale(settlement.defaultFractionDigits, RoundingMode.HALF_UP)
            val impliedRate = base.divide(amount.amount, IMPLIED_FX_RATE_SCALE, RoundingMode.HALF_UP)
            return impliedRate to Money.of(base, settlementCcy)
        }

        val rate = fxRatePort.getRate(paymentCcy, settlementCcy)
            ?: throw FxRateUnavailableException("No FX rate quoted for $paymentCcy/$settlementCcy")
        val baseAmount = amount.amount
            .multiply(rate.askRate)
            .setScale(settlement.defaultFractionDigits, RoundingMode.HALF_UP)
        return rate.askRate to Money.of(baseAmount, settlementCcy)
    }

    /**
     * The loser of a concurrent duplicate-submission race: both contenders passed the replay
     * check before either committed, and this transaction died on uq_transactions_idempotency.
     * Recover to the same contract as the sequential path — return the winner's transaction and
     * start no second payment workflow. Anything that is not the idempotency-key conflict
     * propagates untouched.
     */
    private suspend fun recoverConcurrentReplay(e: RuntimeException, idempotencyKey: String): Transaction {
        // transactions is range-partitioned by booking_date: the violation surfaces under the
        // per-partition auto-generated name (transactions_<year>_idempotency_key_booking_date_key),
        // not the parent's uq_transactions_idempotency — match the column, not one spelling.
        val isIdempotencyKeyConflict = generateSequence<Throwable>(e) { it.cause.takeIf { c -> c !== it } }
            .any { it.message?.contains("idempotency", ignoreCase = true) == true }
        if (!isIdempotencyKeyConflict) throw e
        return transactionRepository.findByIdempotencyKey(idempotencyKey) ?: throw e
    }

    private fun generateReferenceNumber(): String {
        val timestamp = clock.millis().toString()
        val random = (1000..9999).random()
        return "TXN$timestamp$random"
    }
}

class TransactionNotFoundException(message: String) : RuntimeException(message)

/**
 * A transaction update raced a concurrent modification (#465): the caller's domain object was
 * read at a version the row no longer has — e.g. two reversals with distinct idempotency keys
 * both saw COMPLETED, and only the winner may flip it (each extra winner would initiate one
 * extra reversal credit). Dedicated type (not IllegalStateException — two competing mappers,
 * libs 422 vs service, non-deterministic per request; see issue #526) mapped to 409.
 */
class TransactionUpdateConflictException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class FxRateUnavailableException(message: String) : RuntimeException(message)
