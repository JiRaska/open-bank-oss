// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.usecase

import com.openbank.libs.iso20022.Pacs004Reader
import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepa.application.port.`in`.CreateSepaPaymentCommand
import com.openbank.sepa.application.port.`in`.HandlePaymentReturnCommand
import com.openbank.sepa.application.port.`in`.ListSepaPaymentsQuery
import com.openbank.sepa.application.port.`in`.SepaPaymentUseCase
import com.openbank.sepa.application.port.`in`.TransitionSepaPaymentStatusCommand
import com.openbank.sepa.application.port.out.ReversalPort
import com.openbank.sepa.application.port.out.ReversalUnavailableException
import com.openbank.sepa.application.port.out.SepaPaymentEventPublisher
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.application.workflow.SepaPaymentWorkflow
import com.openbank.sepa.domain.event.RETURN_EVIDENCE_EVENT_TYPE
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaRejectReason
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

class SepaPaymentNotFoundException(paymentId: UUID) : RuntimeException("SEPA payment not found: $paymentId")
class InvalidSepaPaymentStateTransitionException(message: String) : RuntimeException(message)

@Suppress("TooManyFunctions", "LongParameterList", "UnusedPrivateMember")
@ApplicationScoped
class SepaPaymentService(
    private val paymentRepository: SepaPaymentRepository,
    private val eventPublisher: SepaPaymentEventPublisher,
    private val reversalPort: ReversalPort,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-sepa-payment")
    private val temporalTaskQueue: String,
    private val workflowClient: WorkflowClient,
    private val clock: Clock,
) : SepaPaymentUseCase {

    @Inject
    constructor(
        paymentRepository: SepaPaymentRepository,
        eventPublisher: SepaPaymentEventPublisher,
        reversalPort: ReversalPort,
        metrics: DomainMetrics,
        @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-sepa-payment")
        temporalTaskQueue: String,
        workflowClient: WorkflowClient,
    ) : this(
        paymentRepository,
        eventPublisher,
        reversalPort,
        metrics,
        temporalTaskQueue,
        workflowClient,
        Clock.systemUTC(),
    )

    private val log = Logger.getLogger(SepaPaymentService::class.java)

    companion object {
        private const val PAYMENT_CREATED_EVENT = "sepa.payment.created"
        private const val PAYMENT_STATUS_CHANGED_EVENT = "sepa.payment.status-changed"

        private const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
        private const val ALERT_AML_HOLD = "AML_HOLD"
        private const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"
    }

    override suspend fun createPayment(command: CreateSepaPaymentCommand): SepaPayment {
        paymentRepository.findByIdempotencyKey(command.idempotencyKey)?.let { return it }

        val now = Instant.now(clock)
        val payment = SepaPayment(
            id = UUID.randomUUID(),
            idempotencyKey = command.idempotencyKey,
            type = command.type,
            status = SepaPaymentStatus.RECEIVED,
            debtorAccountId = command.debtorAccountId,
            debtorIban = command.debtorIban.trim(),
            debtorName = command.debtorName.trim(),
            creditorIban = command.creditorIban.trim(),
            creditorName = command.creditorName.trim(),
            creditorBic = command.creditorBic?.trim(),
            amount = command.amount,
            currency = command.currency.trim().uppercase(Locale.getDefault()),
            remittanceInfo = command.remittanceInfo?.trim(),
            endToEndId = command.endToEndId?.trim()?.ifBlank { null } ?: generateEndToEndId(),
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        )

        val received = paymentRepository.save(
            payment = payment,
            outboxMessage = SepaPaymentOutboxMessage(
                aggregateId = payment.id,
                eventType = PAYMENT_CREATED_EVENT,
                payload = eventPublisher.paymentCreatedPayload(payment),
                createdAt = now,
            ),
        )

        metrics.paymentSubmitted(payment.type.name.lowercase(), payment.currency)

        // ADR-0120 Phase 6 (issue #1917): Temporal is the sole orchestrator. SepaPaymentWorkflow runs
        // screening → shadow fraud scoring → scheme submission → settle; the legacy in-service flow
        // (applyScreening/scoreFraudShadow/submitToScheme) is retired. Fire-and-forget start; the
        // payment is returned RECEIVED and the workflow drives it to its terminal state.
        val stub = workflowClient.newWorkflowStub(
            SepaPaymentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(temporalTaskQueue)
                .setWorkflowId("sepa-payment-${received.id}")
                .build(),
        )
        WorkflowClient.start(stub::process, received.id)
        return received
    }

    /**
     * @param evidencePayload when non-null, a non-repudiation record written into the outbox in the
     * SAME transaction as the transition (issue #6056). Only the `/returns` path supplies one today.
     */
    private suspend fun persistTransition(
        payment: SepaPayment,
        target: SepaPaymentStatus,
        reason: SepaRejectReason?,
        detail: String?,
        transactionId: UUID? = payment.transactionId,
        evidencePayload: String? = null,
    ): SepaPayment {
        val now = Instant.now(clock)
        val updated = payment.transitionTo(target, reason, detail, clock).let {
            if (transactionId != null) it.copy(transactionId = transactionId) else it
        }
        val statusMessage = SepaPaymentOutboxMessage(
            aggregateId = updated.id,
            eventType = PAYMENT_STATUS_CHANGED_EVENT,
            payload = eventPublisher.statusChangedPayload(payment, updated),
            createdAt = now,
        )
        val saved = if (evidencePayload == null) {
            paymentRepository.update(payment = updated, outboxMessage = statusMessage)
        } else {
            paymentRepository.updateWithEvidence(
                payment = updated,
                outboxMessage = statusMessage,
                evidenceMessage = SepaPaymentOutboxMessage(
                    aggregateId = updated.id,
                    eventType = RETURN_EVIDENCE_EVENT_TYPE,
                    payload = evidencePayload,
                    createdAt = now,
                ),
            )
        }

        val terminalOutcomes = setOf(
            SepaPaymentStatus.COMPLETED,
            SepaPaymentStatus.REJECTED,
            SepaPaymentStatus.RETURNED,
            SepaPaymentStatus.CANCELLED,
        )
        if (target in terminalOutcomes) {
            val outcome = target.name.lowercase()
            metrics.paymentCompleted(payment.type.name.lowercase(), payment.currency, outcome)
            payment.createdAt.let { start ->
                metrics.paymentProcessingDuration(
                    payment.type.name.lowercase(),
                    outcome,
                    Duration.between(start, now),
                )
            }
        }

        return saved
    }

    override suspend fun getPayment(paymentId: UUID): SepaPayment =
        paymentRepository.findById(paymentId) ?: throw SepaPaymentNotFoundException(paymentId)

    override suspend fun listPayments(query: ListSepaPaymentsQuery): List<SepaPayment> = paymentRepository.list(
        status = query.status,
        debtorAccountId = query.debtorAccountId,
        limit = query.limit.coerceIn(1, 200),
        offset = query.offset.coerceAtLeast(0),
    )

    override suspend fun transitionStatus(command: TransitionSepaPaymentStatusCommand): SepaPayment {
        val payment = paymentRepository.findById(command.paymentId)
            ?: throw SepaPaymentNotFoundException(command.paymentId)

        if (!payment.canTransitionTo(command.targetStatus)) {
            throw InvalidSepaPaymentStateTransitionException(
                "Invalid SEPA payment status transition: ${payment.status} -> ${command.targetStatus}",
            )
        }

        val updated = try {
            payment.transitionTo(command.targetStatus, command.rejectReason, command.rejectDetail, clock)
        } catch (ex: IllegalArgumentException) {
            throw InvalidSepaPaymentStateTransitionException(ex.message ?: "Invalid SEPA payment state transition")
        }

        return paymentRepository.update(
            payment = updated,
            outboxMessage = SepaPaymentOutboxMessage(
                aggregateId = updated.id,
                eventType = PAYMENT_STATUS_CHANGED_EVENT,
                payload = eventPublisher.statusChangedPayload(payment, updated),
                createdAt = Instant.now(clock),
            ),
        )
    }

    /**
     * Handles an inbound pacs.004 payment return from the clearing simulator (ADR-0109).
     * Parses the XML, resolves the original payment by endToEndId, triggers a ledger reversal
     * in transaction-service (best-effort: failure is logged, transition still proceeds),
     * and advances the payment to RETURNED.
     */
    override suspend fun handlePaymentReturn(command: HandlePaymentReturnCommand): SepaPayment {
        val returnMsg = try {
            Pacs004Reader().read(command.pacs004Xml)
        } catch (ex: Exception) {
            throw IllegalArgumentException("Invalid pacs.004: ${ex.message}", ex)
        }

        val endToEndId = returnMsg.originalEndToEndId
            ?: throw IllegalArgumentException("pacs.004 missing OrgnlEndToEndId")

        val payment = paymentRepository.findByEndToEndId(endToEndId)
            ?: throw IllegalArgumentException("No payment found for endToEndId: $endToEndId")

        // Idempotent: already returned
        if (payment.status == SepaPaymentStatus.RETURNED) return payment

        val txId = payment.transactionId
        var reversalPerformed = false
        if (txId != null) {
            try {
                reversalPort.reverseTransaction(
                    transactionId = txId,
                    idempotencyKey = "sepa-reversal-${payment.id}",
                    reason = returnMsg.returnReasonCode ?: "return",
                )
                reversalPerformed = true
            } catch (ex: ReversalUnavailableException) {
                log.warnf(
                    ex,
                    "Reversal unavailable for payment %s — transitioning to RETURNED without ledger reversal",
                    payment.id,
                )
            }
        } else {
            log.warnf(
                "No transactionId on payment %s — transitioning to RETURNED without ledger reversal",
                payment.id,
            )
        }

        return persistTransition(
            payment,
            SepaPaymentStatus.RETURNED,
            null,
            returnMsg.returnReasonCode,
            // The evidence record and the transition commit together (issue #6056). `reversalPerformed`
            // is the measured outcome of the call above, not the intent — an evidence record that
            // claims a reversal a `ReversalUnavailableException` prevented would be worse than none.
            evidencePayload = eventPublisher.returnEvidencePayload(
                payment = payment,
                originalEndToEndId = endToEndId,
                returnReasonCode = returnMsg.returnReasonCode,
                actorId = command.actorId,
                actorType = command.actorType,
                correlationId = command.correlationId,
                reversalPerformed = reversalPerformed,
            ),
        )
    }

    private fun generateEndToEndId(): String = "E2E${clock.millis()}${(1000..9999).random()}"
}
