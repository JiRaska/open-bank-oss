// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.`in`.DomesticPaymentUseCase
import com.openbank.domestic.application.port.`in`.ListDomesticPaymentsQuery
import com.openbank.domestic.application.port.`in`.TransitionDomesticPaymentStatusCommand
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.AmlCasePort
import com.openbank.domestic.application.port.out.AmlCaseRiskLevel
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.FraudScoreCommand
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.application.port.out.FraudVerdict
import com.openbank.domestic.application.port.out.OpenAmlCaseCommand
import com.openbank.domestic.application.port.out.SanctionsScreeningPort
import com.openbank.domestic.application.port.out.SchemeGatewayPort
import com.openbank.domestic.application.port.out.SchemeGatewayUnavailableException
import com.openbank.domestic.application.port.out.ScreeningUnavailableException
import com.openbank.domestic.application.port.out.SettlementPort
import com.openbank.domestic.application.port.out.SettlementUnavailableException
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.domestic.domain.screening.ScreeningDecision
import com.openbank.domestic.domain.screening.ScreeningMatchStatus
import com.openbank.domestic.domain.screening.ScreeningPolicy
import com.openbank.domestic.domain.screening.ScreeningResult
import com.openbank.domestic.domain.screening.ScreeningRole
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
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

class DomesticPaymentNotFoundException(paymentId: UUID) : RuntimeException("Domestic payment not found: $paymentId")
class InvalidDomesticPaymentStateTransitionException(message: String) : RuntimeException(message)

@Suppress("TooManyFunctions", "LongParameterList")
@ApplicationScoped
class DomesticPaymentService(
    private val paymentRepository: DomesticPaymentRepository,
    private val eventPublisher: DomesticPaymentEventPublisher,
    private val screeningPort: SanctionsScreeningPort,
    private val amlCasePort: AmlCasePort,
    private val fraudScoringPort: FraudScoringPort,
    private val schemeGatewayPort: SchemeGatewayPort,
    private val settlementPort: SettlementPort,
    private val accountLookupPort: AccountLookupPort,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.temporal.enabled", defaultValue = "false")
    private val temporalEnabled: Boolean,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
    private val temporalTaskQueue: String,
    @ConfigProperty(name = "openbank.domestic.scheme-submission.enabled", defaultValue = "false")
    private val schemeSubmissionEnabled: Boolean,
    @ConfigProperty(name = "openbank.domestic.fraud.enforcement-enabled", defaultValue = "false")
    private val fraudEnforcementEnabled: Boolean,
    private val workflowClient: WorkflowClient,
    private val clock: Clock,
) : DomesticPaymentUseCase {

    @Inject
    constructor(
        paymentRepository: DomesticPaymentRepository,
        eventPublisher: DomesticPaymentEventPublisher,
        screeningPort: SanctionsScreeningPort,
        amlCasePort: AmlCasePort,
        fraudScoringPort: FraudScoringPort,
        schemeGatewayPort: SchemeGatewayPort,
        settlementPort: SettlementPort,
        accountLookupPort: AccountLookupPort,
        metrics: DomainMetrics,
        @ConfigProperty(name = "openbank.temporal.enabled", defaultValue = "false")
        temporalEnabled: Boolean,
        @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
        temporalTaskQueue: String,
        @ConfigProperty(name = "openbank.domestic.scheme-submission.enabled", defaultValue = "false")
        schemeSubmissionEnabled: Boolean,
        @ConfigProperty(name = "openbank.domestic.fraud.enforcement-enabled", defaultValue = "false")
        fraudEnforcementEnabled: Boolean,
        workflowClient: WorkflowClient,
    ) : this(
        paymentRepository, eventPublisher, screeningPort, amlCasePort, fraudScoringPort,
        schemeGatewayPort, settlementPort, accountLookupPort, metrics, temporalEnabled, temporalTaskQueue,
        schemeSubmissionEnabled, fraudEnforcementEnabled, workflowClient, Clock.systemUTC(),
    )

    private val log = Logger.getLogger(DomesticPaymentService::class.java)

    companion object {
        private const val PAYMENT_CREATED_EVENT = "domestic.payment.created"
        private const val PAYMENT_STATUS_CHANGED_EVENT = "domestic.payment.status-changed"

        private const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
        private const val ALERT_AML_HOLD = "AML_HOLD"
        private const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"
        private const val ALERT_FRAUD_REVIEW = "FRAUD_REVIEW"

        private const val OWN_BANK_CODE = "0000"
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
            ),
        )

        metrics.paymentSubmitted("domestic", payment.currency)

        if (temporalEnabled) {
            // ADR-0101 P2: delegate durable orchestration to Temporal — fire-and-forget start.
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

        // ADR-0032: screening is the first processing step, run synchronously after the RECEIVED row
        // is durably persisted (so the payment is never lost if screening then fails). Fraud scoring
        // (ADR-0084 §4.2) is the second gate, consulted only for a payment screening has cleared —
        // see applyScreening's CLEAR branch.
        val screened = applyScreening(received)

        return submitToScheme(screened)
    }

    /**
     * ADR-0104 D4: once the payment is VALIDATED, build a real `pacs.008` and submit it to the
     * scheme gateway (Czech CERTIS / the clearing simulator today). `ACSC` → SENT_TO_CLEARING;
     * `RJCT` → REJECTED. Fails closed: if the gateway is unreachable the payment stays VALIDATED
     * (the operator retries or a manual transition handles it). No-op unless the pilot flag is on.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun submitToScheme(payment: DomesticPayment): DomesticPayment {
        if (!schemeSubmissionEnabled || payment.status != DomesticPaymentStatus.VALIDATED) return payment

        return try {
            val outcome = schemeGatewayPort.submit(payment)
            if (outcome.accepted) {
                val sentToClearing =
                    persistTransition(payment, DomesticPaymentStatus.SENT_TO_CLEARING, null, null)
                attemptSettlement(sentToClearing)
            } else {
                val reason = mapSchemeReason(outcome.reasonCode)
                persistTransition(
                    payment,
                    DomesticPaymentStatus.REJECTED,
                    reason,
                    "scheme reject (pacs.002): ${outcome.reasonCode ?: "unspecified"}",
                )
            }
        } catch (ex: SchemeGatewayUnavailableException) {
            log.warnf(ex, "Scheme gateway unavailable for payment %s; holding in VALIDATED", payment.id)
            payment
        } catch (ex: Exception) {
            log.warnf(ex, "Unexpected error during scheme submission for payment %s; holding in VALIDATED", payment.id)
            payment
        }
    }

    /**
     * ADR-0108: book the funds in transaction-service after the scheme confirms ACSC.
     * On success → SETTLED. On [SettlementUnavailableException] → warn and stay in
     * SENT_TO_CLEARING; a Temporal retry or operator intervention will complete it.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun attemptSettlement(payment: DomesticPayment): DomesticPayment = try {
        settlementPort.settle(payment)
        persistTransition(payment, DomesticPaymentStatus.SETTLED, null, null)
    } catch (ex: SettlementUnavailableException) {
        log.warnf(
            ex,
            "Settlement unavailable for payment %s; holding in SENT_TO_CLEARING",
            payment.id,
        )
        payment
    } catch (ex: Exception) {
        log.warnf(
            ex,
            "Unexpected error during settlement for payment %s; holding in SENT_TO_CLEARING",
            payment.id,
        )
        payment
    }

    private fun mapSchemeReason(code: String?): DomesticRejectReason = when (code) {
        "AC04" -> DomesticRejectReason.BENEFICIARY_ACCOUNT_CLOSED
        "AC06" -> DomesticRejectReason.BENEFICIARY_ACCOUNT_CLOSED
        "RC01" -> DomesticRejectReason.INVALID_BANK_CODE
        "AM05" -> DomesticRejectReason.INSUFFICIENT_FUNDS
        else -> DomesticRejectReason.TECHNICAL_ERROR
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
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            settledAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * The fraud gate for a payment screening has cleared (ADR-0084 §4.2). Always scores — the
     * verdict is metered + audited by fraud-service regardless of enforcement — but only ACTS on
     * it when `openbank.domestic.fraud.enforcement-enabled` is true (default false, a
     * runbook-gated rollout flip, same convention as the four-eyes `enforce` toggle). With
     * enforcement off, or verdict ALLOW, this persists VALIDATED exactly like the pre-Phase-2
     * shadow path. REVIEW/CHALLENGE hold the payment in RECEIVED (same shape as an AML REVIEW
     * hold: a case is opened, no further transition, a human releases it) rather than persisting
     * anything — mirrors [applyScreening]'s own hold-vs-persist split. DECLINE rejects outright.
     * The adapter is fail-open (`FraudScoringAdapter`), so an unreachable fraud-service always
     * scores ALLOW here — this gate can only ever add friction, never remove availability.
     */
    private suspend fun applyFraudGate(payment: DomesticPayment): DomesticPayment {
        val outcome = fraudScoringPort.score(
            FraudScoreCommand(
                amount = payment.amount,
                currency = payment.currency,
                rail = "DOMESTIC",
                accountId = payment.debtorAccountId,
                counterpartyId = null,
            ),
        )
        if (outcome.verdict == FraudVerdict.ALLOW) {
            return persistTransition(payment, DomesticPaymentStatus.VALIDATED, null, null)
        }

        val mode = if (fraudEnforcementEnabled) "ENFORCED" else "SHADOW"
        log.infof(
            "Fraud %s verdict %s (score=%d, rules=%s, reasons=%s) for payment %s",
            mode,
            outcome.verdict,
            outcome.score,
            outcome.ruleVersion,
            outcome.reasons,
            payment.id,
        )
        if (!fraudEnforcementEnabled) {
            return persistTransition(payment, DomesticPaymentStatus.VALIDATED, null, null)
        }

        val detail = "verdict=${outcome.verdict} score=${outcome.score} rules=${outcome.ruleVersion} " +
            "reasons=${outcome.reasons.joinToString()}"
        return when (outcome.verdict) {
            FraudVerdict.DECLINE -> {
                openCaseQuietly(payment, AmlCaseRiskLevel.CRITICAL, ALERT_FRAUD_REVIEW, detail, null)
                persistTransition(payment, DomesticPaymentStatus.REJECTED, DomesticRejectReason.FRAUD_SUSPECTED, detail)
            }
            FraudVerdict.REVIEW, FraudVerdict.CHALLENGE -> {
                openCaseQuietly(payment, AmlCaseRiskLevel.HIGH, ALERT_FRAUD_REVIEW, detail, null)
                payment
            }
            FraudVerdict.ALLOW -> persistTransition(payment, DomesticPaymentStatus.VALIDATED, null, null)
        }
    }

    /**
     * Screens both names and applies the [ScreeningPolicy] verdict (ADR-0032 §B). Fails closed
     * (§C): if the sanctions service is unreachable the payment is held in RECEIVED, never released.
     */
    private suspend fun applyScreening(payment: DomesticPayment): DomesticPayment {
        if (payment.transferScope == DomesticTransferScope.OWN_ACCOUNTS ||
            payment.transferScope == DomesticTransferScope.TECHNICAL_ACCOUNT
        ) {
            log.infof("%s payment %s — screening skipped (SDD)", payment.transferScope, payment.id)
            return persistTransition(payment, DomesticPaymentStatus.VALIDATED, null, null)
        }

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
            ScreeningDecision.CLEAR -> applyFraudGate(payment)

            ScreeningDecision.REVIEW -> {
                results.filter {
                    it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
                }.forEach { metrics.sanctionsHit(it.role.name.lowercase(), "review") }
                openCaseQuietly(payment, AmlCaseRiskLevel.HIGH, ALERT_AML_HOLD, detail(results), matchedEntity(results))
                payment
            }

            ScreeningDecision.BLOCK -> {
                results.filter {
                    it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
                }.forEach { metrics.sanctionsHit(it.role.name.lowercase(), "block") }
                val detail = detail(results)
                openCaseQuietly(payment, AmlCaseRiskLevel.CRITICAL, ALERT_SANCTIONS_HIT, detail, matchedEntity(results))
                persistTransition(payment, DomesticPaymentStatus.REJECTED, DomesticRejectReason.SANCTIONS_HIT, detail)
            }
        }
    }

    private suspend fun persistTransition(
        payment: DomesticPayment,
        target: DomesticPaymentStatus,
        reason: DomesticRejectReason?,
        detail: String?,
    ): DomesticPayment {
        val now = Instant.now(clock)
        val updated = payment.transitionTo(target, reason, detail, clock)
        val saved = paymentRepository.update(
            payment = updated,
            outboxMessage = OutboxMessage(
                aggregateId = updated.id,
                eventType = PAYMENT_STATUS_CHANGED_EVENT,
                payload = eventPublisher.statusChangedPayload(payment, updated),
            ),
        )
        val terminalOutcomes = setOf(
            DomesticPaymentStatus.SETTLED,
            DomesticPaymentStatus.REJECTED,
            DomesticPaymentStatus.RETURNED,
            DomesticPaymentStatus.CANCELLED,
        )
        if (target in terminalOutcomes) {
            val outcome = target.name.lowercase()
            metrics.paymentCompleted("domestic", payment.currency, outcome)
            metrics.paymentProcessingDuration(
                "domestic",
                outcome,
                Duration.between(payment.createdAt, now),
            )
        }
        return saved
    }

    /** Opening the AML case is best-effort: a case-store outage must not flip the screening verdict. */
    private suspend fun openCaseQuietly(
        payment: DomesticPayment,
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
                    customerReference =
                    "${payment.debtorName} / ${payment.debtorAccountNumber}/${payment.debtorBankCode}",
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
