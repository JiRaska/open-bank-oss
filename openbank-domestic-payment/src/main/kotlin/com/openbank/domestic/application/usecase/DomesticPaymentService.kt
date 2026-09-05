// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentResult
import com.openbank.domestic.application.port.`in`.DelegatedDomesticPaymentResult
import com.openbank.domestic.application.port.`in`.DelegatedDomesticPaymentUseCase
import com.openbank.domestic.application.port.`in`.DomesticPaymentUseCase
import com.openbank.domestic.application.port.`in`.ListDomesticPaymentsQuery
import com.openbank.domestic.application.port.`in`.TransitionDomesticPaymentStatusCommand
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DelegatedPaymentSaveOutcome
import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.domain.model.DelegatedSpendBindingState
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DomesticPaymentNotFoundException(paymentId: UUID) : RuntimeException("Domestic payment not found: $paymentId")
class InvalidDomesticPaymentStateTransitionException(message: String) : RuntimeException(message)
class DomesticPaymentIdempotencyConflictException :
    RuntimeException(
        "Idempotency-Key is already bound to another domestic payment request",
    )

@Suppress("TooManyFunctions", "LongParameterList")
@ApplicationScoped
class DomesticPaymentService(
    private val paymentRepository: DomesticPaymentRepository,
    private val delegatedSpendBindingRepository: DelegatedSpendBindingRepository,
    private val eventPublisher: DomesticPaymentEventPublisher,
    private val accountLookupPort: AccountLookupPort,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
    private val temporalTaskQueue: String,
    private val workflowClient: WorkflowClient,
    private val clock: Clock,
) : DomesticPaymentUseCase,
    DelegatedDomesticPaymentUseCase {

    @Inject
    constructor(
        paymentRepository: DomesticPaymentRepository,
        delegatedSpendBindingRepository: DelegatedSpendBindingRepository,
        eventPublisher: DomesticPaymentEventPublisher,
        accountLookupPort: AccountLookupPort,
        metrics: DomainMetrics,
        @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
        temporalTaskQueue: String,
        workflowClient: WorkflowClient,
    ) : this(
        paymentRepository,
        delegatedSpendBindingRepository,
        eventPublisher,
        accountLookupPort,
        metrics,
        temporalTaskQueue,
        workflowClient,
        Clock.systemUTC(),
    )

    companion object {
        private const val PAYMENT_CREATED_EVENT = "domestic.payment.created"
        private const val PAYMENT_STATUS_CHANGED_EVENT = "domestic.payment.status-changed"

        private const val OWN_BANK_CODE = "0000"
        private const val IDEMPOTENCY_KEY_MAX_LENGTH = 128
        private const val DELEGATED_ACTOR_SCOPE_PREFIX = "openbank:delegated-spend:v1"

        private val TERMINAL_STATUSES = setOf(
            DomesticPaymentStatus.SETTLED,
            DomesticPaymentStatus.REJECTED,
            DomesticPaymentStatus.RETURNED,
            DomesticPaymentStatus.CANCELLED,
        )
    }

    /** Derive transferScope server-side — never trust the value from the client. */
    private suspend fun deriveTransferScope(
        creditorBankCode: String,
        creditorIban: String,
        debitOwnerPartyId: UUID?,
    ): DomesticTransferScope {
        if (creditorBankCode.trim() != OWN_BANK_CODE) return DomesticTransferScope.EXTERNAL
        val creditorPartyId = accountLookupPort.findPartyByIban(creditorIban)
        return when {
            creditorPartyId == null -> DomesticTransferScope.INTERNAL_CLIENT
            creditorPartyId == debitOwnerPartyId -> DomesticTransferScope.OWN_ACCOUNTS
            else -> DomesticTransferScope.INTERNAL_CLIENT
        }
    }

    override suspend fun createPayment(command: CreateDomesticPaymentCommand): CreateDomesticPaymentResult {
        require(command.delegationId == null && command.reservationId == null) {
            "The public domestic-payment use case accepts owner-funded payments only"
        }
        return checkNotNull(
            createPaymentInternal(
                command = command,
                delegated = false,
                debitOwnerPartyId = command.actorId,
            ).acceptedOrNull(),
        ) {
            "Owner-funded creation cannot depend on a delegated reservation projection"
        }
    }

    override suspend fun createDelegatedPayment(
        reservationId: UUID,
        command: CreateDomesticPaymentCommand,
    ): DelegatedDomesticPaymentResult {
        val binding = delegatedSpendBindingRepository.findByReservationId(reservationId)
            ?: return DelegatedDomesticPaymentResult.ReservationProjectionPending
        if (binding.bindingState == DelegatedSpendBindingState.FINALIZED_ABSENT) {
            return DelegatedDomesticPaymentResult.ReservationFinalizedAbsent
        }

        val snapshot = binding.snapshot
        val normalizedCommand = DomesticPaymentRequestFingerprint.normalize(command)
        val debtorIban = CzechDomesticIban.fromAccountNumber(
            accountNumber = normalizedCommand.debtorAccountNumber,
            bankCode = normalizedCommand.debtorBankCode,
        ) ?: return DelegatedDomesticPaymentResult.ReservationMismatch(
            "Debtor account coordinates are not a canonical Czech account",
        )
        val resolvedAccountId = accountLookupPort.findAccountIdByIban(debtorIban)
            ?: return DelegatedDomesticPaymentResult.AccountAuthorityUnavailable
        if (resolvedAccountId != snapshot.resourceId) {
            return DelegatedDomesticPaymentResult.ReservationMismatch(
                "Debtor account coordinates do not resolve to the reserved account",
            )
        }
        val resolvedOwnerPartyId = accountLookupPort.findPartyByAccountId(snapshot.resourceId)
            ?: return DelegatedDomesticPaymentResult.AccountAuthorityUnavailable
        if (resolvedOwnerPartyId != snapshot.grantorPartyId) {
            return DelegatedDomesticPaymentResult.ReservationMismatch(
                "Reserved account owner does not equal the delegation grantor",
            )
        }

        val trustedCommand = normalizedCommand.copy(
            debtorAccountId = snapshot.resourceId,
            actorId = snapshot.granteePartyId,
            actorScope = "$DELEGATED_ACTOR_SCOPE_PREFIX:${snapshot.granteePartyId}",
            delegationId = snapshot.delegationId,
            reservationId = snapshot.reservationId,
        )
        return createPaymentInternal(
            command = trustedCommand,
            delegated = true,
            // Carry the independently resolved owner into the repository. Its binding-row lock
            // compares this proof with the stored grantor before any write can commit.
            debitOwnerPartyId = resolvedOwnerPartyId,
        )
    }

    private suspend fun createPaymentInternal(
        command: CreateDomesticPaymentCommand,
        delegated: Boolean,
        debitOwnerPartyId: UUID?,
    ): DelegatedDomesticPaymentResult {
        require(command.idempotencyKey.isNotBlank()) { "Idempotency-Key header is required" }
        require(command.idempotencyKey.length <= IDEMPOTENCY_KEY_MAX_LENGTH) {
            "Idempotency-Key must be at most $IDEMPOTENCY_KEY_MAX_LENGTH characters"
        }
        val normalized = DomesticPaymentRequestFingerprint.normalize(command)
        val requestFingerprint = DomesticPaymentRequestFingerprint.sha256(normalized)

        paymentRepository.findByIdempotencyKey(normalized.idempotencyKey)?.let { existing ->
            verifyReplay(existing, requestFingerprint)
            return DelegatedDomesticPaymentResult.Accepted(CreateDomesticPaymentResult(existing, replayed = true))
        }

        val transferScope = deriveTransferScope(
            creditorBankCode = normalized.creditorBankCode,
            creditorIban = normalized.creditorAccountNumber,
            debitOwnerPartyId = debitOwnerPartyId,
        )
        val technicalAccountCode = normalized.technicalAccountCode
        require(transferScope != DomesticTransferScope.TECHNICAL_ACCOUNT || !technicalAccountCode.isNullOrBlank()) {
            "technicalAccountCode is required for TECHNICAL_ACCOUNT"
        }

        val payment = buildReceivedPayment(normalized, requestFingerprint, transferScope, technicalAccountCode)

        val createdMessage = createdOutboxMessage(payment, normalized.synthetic)
        val saveOutcome = if (delegated) {
            paymentRepository.saveDelegated(
                payment = payment,
                outboxMessage = createdMessage,
                boundAt = Instant.now(clock),
                debitOwnerPartyId = checkNotNull(debitOwnerPartyId),
            )
        } else {
            DelegatedPaymentSaveOutcome.Created(paymentRepository.save(payment, createdMessage))
        }
        val received = when (saveOutcome) {
            is DelegatedPaymentSaveOutcome.Created -> saveOutcome.payment

            is DelegatedPaymentSaveOutcome.Replayed -> saveOutcome.payment

            DelegatedPaymentSaveOutcome.ProjectionMissing ->
                return DelegatedDomesticPaymentResult.ReservationProjectionPending

            DelegatedPaymentSaveOutcome.FinalizedAbsent ->
                return DelegatedDomesticPaymentResult.ReservationFinalizedAbsent

            is DelegatedPaymentSaveOutcome.TupleMismatch ->
                return DelegatedDomesticPaymentResult.ReservationMismatch(saveOutcome.reason)
        }

        // A concurrent request may have won the unique idempotency-key insert after our fast-path
        // lookup. The repository returns that winner after rolling back the losing transaction, so
        // no duplicate outbox entry exists. It is still a replay only when the durable fingerprints
        // match; a different or legacy-null binding fails closed.
        if (received.id != payment.id) {
            verifyReplay(received, requestFingerprint)
            return DelegatedDomesticPaymentResult.Accepted(CreateDomesticPaymentResult(received, replayed = true))
        }

        metrics.paymentSubmitted("domestic", payment.currency)

        // ADR-0120 Phase 6 (issue #1917): Temporal is the sole orchestrator. DomesticPaymentWorkflow runs
        // screening → shadow fraud scoring → scheme submission → settle; the legacy in-service flow
        // (applyScreening/applyFraudGate/submitToScheme/attemptSettlement) is retired. Fire-and-forget
        // start; the payment is returned RECEIVED and the workflow drives it to its terminal state.
        startWorkflow(received.id)
        return DelegatedDomesticPaymentResult.Accepted(CreateDomesticPaymentResult(received, replayed = false))
    }

    private fun createdOutboxMessage(payment: DomesticPayment, synthetic: Boolean): OutboxMessage = OutboxMessage(
        aggregateId = payment.id,
        eventType = PAYMENT_CREATED_EVENT,
        payload = eventPublisher.paymentCreatedPayload(payment),
        synthetic = synthetic,
    )

    private fun startWorkflow(paymentId: UUID) {
        val stub = workflowClient.newWorkflowStub(
            DomesticPaymentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(temporalTaskQueue)
                .setWorkflowId("domestic-payment-$paymentId")
                .build(),
        )
        WorkflowClient.start(stub::process, paymentId)
    }

    private fun DelegatedDomesticPaymentResult.acceptedOrNull(): CreateDomesticPaymentResult? =
        (this as? DelegatedDomesticPaymentResult.Accepted)?.result

    private fun verifyReplay(existing: DomesticPayment, requestFingerprint: String) {
        if (existing.requestFingerprint != requestFingerprint) {
            throw DomesticPaymentIdempotencyConflictException()
        }
    }

    /** Assemble the initial RECEIVED payment from the validated command. */
    private fun buildReceivedPayment(
        command: CreateDomesticPaymentCommand,
        requestFingerprint: String,
        transferScope: DomesticTransferScope,
        technicalAccountCode: String?,
    ): DomesticPayment {
        val now = Instant.now(clock)
        return DomesticPayment(
            id = UUID.randomUUID(),
            idempotencyKey = command.idempotencyKey,
            status = DomesticPaymentStatus.RECEIVED,
            debtorAccountId = command.debtorAccountId,
            debtorAccountNumber = command.debtorAccountNumber,
            debtorBankCode = command.debtorBankCode,
            debtorName = command.debtorName,
            creditorAccountNumber = command.creditorAccountNumber,
            creditorBankCode = command.creditorBankCode,
            creditorName = command.creditorName,
            amount = command.amount,
            currency = command.currency,
            variableSymbol = command.variableSymbol,
            specificSymbol = command.specificSymbol,
            constantSymbol = command.constantSymbol,
            messageForPayee = command.messageForPayee,
            priority = command.priority,
            transferScope = transferScope,
            technicalAccountCode = technicalAccountCode,
            statementLabel = command.statementLabel,
            endToEndId = command.endToEndId ?: generateEndToEndId(command.priority),
            initiatedByPartyId = command.actorId,
            requestFingerprint = requestFingerprint,
            delegationId = command.delegationId,
            reservationId = command.reservationId,
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            settledAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    override suspend fun getPayment(paymentId: UUID): DomesticPayment =
        paymentRepository.findById(paymentId) ?: throw DomesticPaymentNotFoundException(paymentId)

    override suspend fun listPayments(query: ListDomesticPaymentsQuery): List<DomesticPayment> = paymentRepository.list(
        status = query.status,
        debtorAccountId = query.debtorAccountId,
        limit = query.limit.coerceIn(1, 200),
        offset = query.offset.coerceAtLeast(0),
    )

    override suspend fun transitionStatus(command: TransitionDomesticPaymentStatusCommand): DomesticPayment {
        val payment = paymentRepository.findById(command.paymentId)
            ?: throw DomesticPaymentNotFoundException(command.paymentId)

        if (!payment.canTransitionTo(command.targetStatus)) {
            throw InvalidDomesticPaymentStateTransitionException(
                "Invalid domestic payment status transition: ${payment.status} -> ${command.targetStatus}",
            )
        }

        val updated = try {
            payment.transitionTo(command.targetStatus, command.rejectReason, command.rejectDetail, clock)
        } catch (ex: IllegalArgumentException) {
            throw InvalidDomesticPaymentStateTransitionException(
                ex.message ?: "Invalid domestic payment state transition",
            )
        }

        // Only paymentSubmitted() was ever called for this rail (createPayment, above) --
        // paymentCompleted()/paymentProcessingDuration() had no call site anywhere in this file, so
        // openbank_payments_completed_total{type="domestic",...} and
        // openbank_payment_processing_duration_seconds{type="domestic",...} could never have a
        // sample regardless of how much domestic-payment traffic occurred (issue #5049). "settled"
        // matches sepa-instant's existing convention for the same terminal concept -- both are
        // covered by openbank-payment-sla's outcome=~"completed|settled" already, so no dashboard
        // change is needed to read it.
        if (updated.status in TERMINAL_STATUSES) {
            val outcome = when (updated.status) {
                DomesticPaymentStatus.SETTLED -> "settled"
                else -> updated.status.name.lowercase()
            }
            metrics.paymentCompleted("domestic", updated.currency, outcome)
            metrics.paymentProcessingDuration(
                "domestic",
                outcome,
                Duration.between(updated.createdAt, Instant.now(clock)),
            )
        }

        return paymentRepository.update(
            payment = updated,
            outboxMessage = OutboxMessage(
                aggregateId = updated.id,
                eventType = PAYMENT_STATUS_CHANGED_EVENT,
                payload = eventPublisher.statusChangedPayload(payment, updated),
            ),
        )
    }

    private fun generateEndToEndId(priority: DomesticPaymentPriority): String =
        "DOM${priority.name.take(1)}${clock.millis()}${(1000..9999).random()}"
}
