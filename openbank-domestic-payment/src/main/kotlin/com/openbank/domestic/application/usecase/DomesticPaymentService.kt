// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.`in`.DomesticPaymentUseCase
import com.openbank.domestic.application.port.`in`.ListDomesticPaymentsQuery
import com.openbank.domestic.application.port.`in`.TransitionDomesticPaymentStatusCommand
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
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
import java.util.Locale
import java.util.UUID

class DomesticPaymentNotFoundException(paymentId: UUID) : RuntimeException("Domestic payment not found: $paymentId")
class InvalidDomesticPaymentStateTransitionException(message: String) : RuntimeException(message)

@Suppress("TooManyFunctions", "LongParameterList")
@ApplicationScoped
class DomesticPaymentService(
    private val paymentRepository: DomesticPaymentRepository,
    private val eventPublisher: DomesticPaymentEventPublisher,
    private val accountLookupPort: AccountLookupPort,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
    private val temporalTaskQueue: String,
    private val workflowClient: WorkflowClient,
    private val clock: Clock,
) : DomesticPaymentUseCase {

    @Inject
    constructor(
        paymentRepository: DomesticPaymentRepository,
        eventPublisher: DomesticPaymentEventPublisher,
        accountLookupPort: AccountLookupPort,
        metrics: DomainMetrics,
        @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
        temporalTaskQueue: String,
        workflowClient: WorkflowClient,
    ) : this(
        paymentRepository,
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
        actorId: UUID?,
    ): DomesticTransferScope {
        if (creditorBankCode.trim() != OWN_BANK_CODE) return DomesticTransferScope.EXTERNAL
        val creditorPartyId = accountLookupPort.findPartyByIban(creditorIban)
        return when {
            creditorPartyId == null -> DomesticTransferScope.INTERNAL_CLIENT
            creditorPartyId == actorId -> DomesticTransferScope.OWN_ACCOUNTS
            else -> DomesticTransferScope.INTERNAL_CLIENT
        }
    }

    override suspend fun createPayment(command: CreateDomesticPaymentCommand): DomesticPayment {
        paymentRepository.findByIdempotencyKey(command.idempotencyKey)?.let { return it }

        val transferScope = deriveTransferScope(
            creditorBankCode = command.creditorBankCode.trim(),
            creditorIban = command.creditorAccountNumber.trim(),
            actorId = command.actorId,
        )
        val technicalAccountCode = command.technicalAccountCode?.trim()?.ifBlank { null }
        require(transferScope != DomesticTransferScope.TECHNICAL_ACCOUNT || !technicalAccountCode.isNullOrBlank()) {
            "technicalAccountCode is required for TECHNICAL_ACCOUNT"
        }

        val payment = buildReceivedPayment(command, transferScope, technicalAccountCode)

        val received = paymentRepository.save(
            payment = payment,
            outboxMessage = OutboxMessage(
                aggregateId = payment.id,
                eventType = PAYMENT_CREATED_EVENT,
                payload = eventPublisher.paymentCreatedPayload(payment),
                synthetic = command.synthetic,
            ),
        )

        metrics.paymentSubmitted("domestic", payment.currency)

        // ADR-0120 Phase 6 (issue #1917): Temporal is the sole orchestrator. DomesticPaymentWorkflow runs
        // screening → shadow fraud scoring → scheme submission → settle; the legacy in-service flow
        // (applyScreening/applyFraudGate/submitToScheme/attemptSettlement) is retired. Fire-and-forget
        // start; the payment is returned RECEIVED and the workflow drives it to its terminal state.
        val stub = workflowClient.newWorkflowStub(
            DomesticPaymentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(temporalTaskQueue)
                .setWorkflowId("domestic-payment-${received.id}")
                .build(),
        )
        WorkflowClient.start(stub::process, received.id)
        return received
    }

    /** Assemble the initial RECEIVED payment from the validated command. */
    private fun buildReceivedPayment(
        command: CreateDomesticPaymentCommand,
        transferScope: DomesticTransferScope,
        technicalAccountCode: String?,
    ): DomesticPayment {
        val now = Instant.now(clock)
        return DomesticPayment(
            id = UUID.randomUUID(),
            idempotencyKey = command.idempotencyKey,
            status = DomesticPaymentStatus.RECEIVED,
            debtorAccountId = command.debtorAccountId,
            debtorAccountNumber = command.debtorAccountNumber.trim(),
            debtorBankCode = command.debtorBankCode.trim(),
            debtorName = command.debtorName.trim(),
            creditorAccountNumber = command.creditorAccountNumber.trim(),
            creditorBankCode = command.creditorBankCode.trim(),
            creditorName = command.creditorName.trim(),
            amount = command.amount,
            currency = command.currency.trim().uppercase(Locale.getDefault()),
            variableSymbol = command.variableSymbol?.trim()?.ifBlank { null },
            specificSymbol = command.specificSymbol?.trim()?.ifBlank { null },
            constantSymbol = command.constantSymbol?.trim()?.ifBlank { null },
            messageForPayee = command.messageForPayee?.trim()?.ifBlank { null },
            priority = command.priority,
            transferScope = transferScope,
            technicalAccountCode = technicalAccountCode,
            statementLabel = command.statementLabel?.trim()?.ifBlank { null },
            endToEndId = command.endToEndId?.trim()?.ifBlank { null } ?: generateEndToEndId(command.priority),
            initiatedByPartyId = command.actorId,
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
