// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.application.usecase

import com.openbank.libs.iso20022.Pacs004Reader
import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepa.application.port.`in`.CreateSepaPaymentCommand
import com.openbank.sepa.application.port.`in`.HandlePaymentReturnCommand
import com.openbank.sepa.application.port.`in`.ListSepaPaymentsQuery
import com.openbank.sepa.application.port.`in`.SepaPaymentUseCase
import com.openbank.sepa.application.port.`in`.TransitionSepaPaymentStatusCommand
import com.openbank.sepa.application.port.out.AmlCasePort
import com.openbank.sepa.application.port.out.AmlCaseRiskLevel
import com.openbank.sepa.application.port.out.FraudScoreCommand
import com.openbank.sepa.application.port.out.FraudScoringPort
import com.openbank.sepa.application.port.out.FraudVerdict
import com.openbank.sepa.application.port.out.OpenAmlCaseCommand
import com.openbank.sepa.application.port.out.ReversalPort
import com.openbank.sepa.application.port.out.ReversalUnavailableException
import com.openbank.sepa.application.port.out.SanctionsScreeningPort
import com.openbank.sepa.application.port.out.SchemeGatewayPort
import com.openbank.sepa.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepa.application.port.out.ScreeningUnavailableException
import com.openbank.sepa.application.port.out.SepaPaymentEventPublisher
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.application.port.out.SettlementPort
import com.openbank.sepa.application.port.out.SettlementUnavailableException
import com.openbank.sepa.application.workflow.SepaPaymentWorkflow
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaRejectReason
import com.openbank.sepa.domain.screening.ScreeningDecision
import com.openbank.sepa.domain.screening.ScreeningMatchStatus
import com.openbank.sepa.domain.screening.ScreeningPolicy
import com.openbank.sepa.domain.screening.ScreeningResult
import com.openbank.sepa.domain.screening.ScreeningRole
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
    private val screeningPort: SanctionsScreeningPort,
    private val amlCasePort: AmlCasePort,
    private val fraudScoringPort: FraudScoringPort,
    private val schemeGatewayPort: SchemeGatewayPort,
    private val settlementPort: SettlementPort,
    private val reversalPort: ReversalPort,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.sepa.scheme-submission.enabled", defaultValue = "false")
    private val schemeSubmissionEnabled: Boolean,
    @ConfigProperty(name = "openbank.temporal.enabled", defaultValue = "false")
    private val temporalEnabled: Boolean,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-sepa-payment")
    private val temporalTaskQueue: String,
    private val workflowClient: WorkflowClient,
    private val clock: Clock,
) : SepaPaymentUseCase {

    @Inject
    constructor(
        paymentRepository: SepaPaymentRepository,
        eventPublisher: SepaPaymentEventPublisher,
        screeningPort: SanctionsScreeningPort,
        amlCasePort: AmlCasePort,
        fraudScoringPort: FraudScoringPort,
        schemeGatewayPort: SchemeGatewayPort,
        settlementPort: SettlementPort,
        reversalPort: ReversalPort,
        metrics: DomainMetrics,
        @ConfigProperty(name = "openbank.sepa.scheme-submission.enabled", defaultValue = "false")
        schemeSubmissionEnabled: Boolean,
        @ConfigProperty(name = "openbank.temporal.enabled", defaultValue = "false")
        temporalEnabled: Boolean,
        @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-sepa-payment")
        temporalTaskQueue: String,
        workflowClient: WorkflowClient,
    ) : this(
        paymentRepository, eventPublisher, screeningPort, amlCasePort, fraudScoringPort, schemeGatewayPort,
        settlementPort, reversalPort, metrics, schemeSubmissionEnabled, temporalEnabled, temporalTaskQueue,
        workflowClient, Clock.systemUTC(),
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

        if (temporalEnabled) {
            // ADR-0101 P1: delegate durable orchestration to Temporal — fire-and-forget start.
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

        // ADR-0032: screening is the first processing step, run synchronously after the RECEIVED row
        // is durably persisted (so the payment is never lost if screening then fails).
        val screened = applyScreening(received)

        // ADR-0084 §4.1 (SHADOW): score for fraud alongside screening, log the verdict, and IGNORE it —
        // the payment proceeds on its screening outcome. Fail-open via the adapter (never blocks/holds).
        scoreFraudShadow(screened)

        // ADR-0104 D3: once screened CLEAR, build a real pacs.008 and submit it to the scheme gateway
        // (the clearing simulator today), advancing on the pacs.002 verdict. Flag-gated and additive —
        // default OFF preserves today's behaviour exactly.
        return submitToScheme(screened)
    }

    /**
     * ADR-0104 D3: submit a VALIDATED payment to the scheme gateway and advance it on the verdict —
     * `ACSC` → PROCESSING (accepted into settlement), `RJCT` → REJECTED with the mapped reason.
     * Fails **closed** (ADR-0032): if the gateway is unreachable the payment stays VALIDATED, never
     * silently released. No-op unless the pilot flag is on and the payment actually cleared screening.
     */
    private suspend fun submitToScheme(payment: SepaPayment): SepaPayment {
        if (!schemeSubmissionEnabled || payment.status != SepaPaymentStatus.VALIDATED) return payment

        val outcome = try {
            schemeGatewayPort.submit(payment)
        } catch (ex: SchemeGatewayUnavailableException) {
            log.warnf(ex, "Scheme gateway unavailable for payment %s; holding in VALIDATED", payment.id)
            return payment
        }

        return if (outcome.accepted) {
            val processing = persistTransition(payment, SepaPaymentStatus.PROCESSING, null, null)
            settleProcessingPayment(processing)
        } else {
            persistTransition(
                payment,
                SepaPaymentStatus.REJECTED,
                mapSchemeReason(outcome.reasonCode),
                "scheme reject (pacs.002): ${outcome.reasonCode ?: "unspecified"}",
            )
        }
    }

    /**
     * ADR-0108: call transaction-service to book funds for a PROCESSING payment. Fail-open:
     * if settlement is unavailable the payment stays in PROCESSING so it can be retried;
     * on success it advances to COMPLETED.
     */
    private suspend fun settleProcessingPayment(payment: SepaPayment): SepaPayment {
        val settlementOutcome = try {
            settlementPort.settle(payment)
        } catch (ex: SettlementUnavailableException) {
            log.warnf(ex, "Settlement unavailable for payment %s; holding in PROCESSING", payment.id)
            return payment
        }
        return if (settlementOutcome.settled) {
            persistTransition(payment, SepaPaymentStatus.COMPLETED, null, null, settlementOutcome.transactionId)
        } else {
            log.warnf("Settlement returned not-settled for payment %s; holding in PROCESSING", payment.id)
            payment
        }
    }

    /** Maps an ISO 20022 `ExternalStatusReason1Code` from the scheme onto a [SepaRejectReason]. */
    private fun mapSchemeReason(code: String?): SepaRejectReason = when (code) {
        "AC04" -> SepaRejectReason.ACCOUNT_CLOSED
        "AC06" -> SepaRejectReason.ACCOUNT_FROZEN
        "RC01" -> SepaRejectReason.INVALID_BIC
        "RR04" -> SepaRejectReason.AML_HOLD
        else -> SepaRejectReason.TECHNICAL_ERROR
    }

    /**
     * Fraud scoring in SHADOW mode (ADR-0084 §1/§4.1): the verdict is observed (logged here; metered +
     * audited by fraud-service), never enforced. A non-ALLOW verdict is logged for the RTS Art. 18
     * baseline; ALLOW is silent. The adapter is fail-open, so this can never affect the payment.
     */
    private suspend fun scoreFraudShadow(payment: SepaPayment) {
        val outcome = fraudScoringPort.score(
            FraudScoreCommand(
                amount = payment.amount,
                currency = payment.currency,
                rail = "SEPA",
                accountId = payment.debtorAccountId,
                counterpartyId = null,
            ),
        )
        if (outcome.verdict != FraudVerdict.ALLOW) {
            log.infof(
                "Fraud SHADOW verdict %s (score=%d, rules=%s) for payment %s — observed, not enforced",
                outcome.verdict,
                outcome.score,
                outcome.ruleVersion,
                payment.id,
            )
        }
    }

    /**
     * Screens both names and applies the [ScreeningPolicy] verdict (ADR-0032 §B). Fails closed
     * (§C): if the sanctions service is unreachable the payment is held in RECEIVED, never released.
     */
    private suspend fun applyScreening(payment: SepaPayment): SepaPayment {
        val results = try {
            listOf(
                screeningPort.screen(payment.debtorName, ScreeningRole.DEBTOR, "${payment.id}:debtor"),
                screeningPort.screen(payment.creditorName, ScreeningRole.CREDITOR, "${payment.id}:creditor"),
            )
        } catch (ex: ScreeningUnavailableException) {
            log.warnf(ex, "Sanctions screening unavailable for payment %s; holding in RECEIVED", payment.id)
            openCaseQuietly(payment, AmlCaseRiskLevel.MEDIUM, ALERT_SCREENING_UNAVAILABLE, ex.message, null)
            return payment
        }

        results.forEach { result ->
            metrics.sanctionsScreening(result.role.name.lowercase())
        }

        return when (ScreeningPolicy.decide(results)) {
            ScreeningDecision.CLEAR ->
                persistTransition(payment, SepaPaymentStatus.VALIDATED, null, null)

            ScreeningDecision.REVIEW -> {
                results.filter {
                    it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
                }
                    .forEach { metrics.sanctionsHit(it.role.name.lowercase(), "review") }
                openCaseQuietly(payment, AmlCaseRiskLevel.HIGH, ALERT_AML_HOLD, detail(results), matchedEntity(results))
                payment // held in RECEIVED for a human decision via the AML case lifecycle
            }

            ScreeningDecision.BLOCK -> {
                results.filter {
                    it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
                }
                    .forEach { metrics.sanctionsHit(it.role.name.lowercase(), "block") }
                val detail = detail(results)
                openCaseQuietly(payment, AmlCaseRiskLevel.CRITICAL, ALERT_SANCTIONS_HIT, detail, matchedEntity(results))
                persistTransition(payment, SepaPaymentStatus.REJECTED, SepaRejectReason.SANCTIONS_HIT, detail)
            }
        }
    }

    private suspend fun persistTransition(
        payment: SepaPayment,
        target: SepaPaymentStatus,
        reason: SepaRejectReason?,
        detail: String?,
        transactionId: UUID? = payment.transactionId,
    ): SepaPayment {
        val now = Instant.now(clock)
        val updated = payment.transitionTo(target, reason, detail, clock).let {
            if (transactionId != null) it.copy(transactionId = transactionId) else it
        }
        val saved = paymentRepository.update(
            payment = updated,
            outboxMessage = SepaPaymentOutboxMessage(
                aggregateId = updated.id,
                eventType = PAYMENT_STATUS_CHANGED_EVENT,
                payload = eventPublisher.statusChangedPayload(payment, updated),
                createdAt = now,
            ),
        )

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

    /** Opening the AML case is best-effort: a case-store outage must not flip the screening verdict. */
    private suspend fun openCaseQuietly(
        payment: SepaPayment,
        riskLevel: AmlCaseRiskLevel,
        alertCode: String,
        detail: String?,
        matchedEntity: String?,
    ) {
        try {
            amlCasePort.openCase(
                OpenAmlCaseCommand(
                    idempotencyKey = "aml-${payment.id}-$alertCode",
                    paymentId = payment.id,
                    debtorAccountId = payment.debtorAccountId,
                    customerReference = "${payment.debtorName} / ${payment.debtorIban}",
                    riskLevel = riskLevel,
                    alertCode = alertCode,
                    alertDetail = detail,
                    matchedEntity = matchedEntity,
                ),
            )
        } catch (ex: Exception) {
            log.errorf(ex, "Failed to open AML case (%s) for payment %s", alertCode, payment.id)
        }
    }

    private fun detail(results: List<ScreeningResult>): String =
        results.filter { it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED }
            .joinToString("; ") { "${it.role} '${it.subject}' ${it.status} score=${it.score}" }
            .ifBlank { "no actionable matches" }

    private fun matchedEntity(results: List<ScreeningResult>): String? =
        results.firstNotNullOfOrNull { it.matchedEntity }

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
        if (txId != null) {
            try {
                reversalPort.reverseTransaction(
                    transactionId = txId,
                    idempotencyKey = "sepa-reversal-${payment.id}",
                    reason = returnMsg.returnReasonCode ?: "return",
                )
            } catch (ex: ReversalUnavailableException) {
                log.warnf(
                    ex,
                    "Reversal unavailable for payment %s — transitioning to RETURNED without ledger reversal",
                    payment.id,
                )
            }
        } else {
            log.warnf("No transactionId on payment %s — transitioning to RETURNED without ledger reversal", payment.id)
        }

        return persistTransition(
            payment,
            SepaPaymentStatus.RETURNED,
            null,
            returnMsg.returnReasonCode,
        )
    }

    private fun generateEndToEndId(): String = "E2E${clock.millis()}${(1000..9999).random()}"
}
