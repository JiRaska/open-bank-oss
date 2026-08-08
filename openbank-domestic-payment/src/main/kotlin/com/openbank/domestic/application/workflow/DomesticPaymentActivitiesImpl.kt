// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

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
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.domestic.domain.screening.ScreeningDecision
import com.openbank.domestic.domain.screening.ScreeningMatchStatus
import com.openbank.domestic.domain.screening.ScreeningPolicy
import com.openbank.domestic.domain.screening.ScreeningResult
import com.openbank.domestic.domain.screening.ScreeningRole
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

private const val PAYMENT_STATUS_CHANGED_EVENT = "domestic.payment.status-changed"
private const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
private const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"

@Suppress("LongParameterList")
@ApplicationScoped
open class DomesticPaymentActivitiesImpl(
    private val paymentRepository: DomesticPaymentRepository,
    private val eventPublisher: DomesticPaymentEventPublisher,
    private val screeningPort: SanctionsScreeningPort,
    private val amlCasePort: AmlCasePort,
    private val fraudScoringPort: FraudScoringPort,
    private val schemeGatewayPort: SchemeGatewayPort,
    private val settlementPort: SettlementPort,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.domestic.scheme-submission.enabled", defaultValue = "false")
    private val schemeSubmissionEnabled: Boolean,
) : DomesticPaymentActivities {

    private val log = Logger.getLogger(DomesticPaymentActivitiesImpl::class.java)

    protected open fun <T> vtx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    override fun screenPayment(paymentId: UUID): ScreeningDecision = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during screening activity")

        when (payment.transferScope) {
            DomesticTransferScope.OWN_ACCOUNTS, DomesticTransferScope.TECHNICAL_ACCOUNT -> {
                log.infof("%s transfer %s — sanctions screening skipped (SDD)", payment.transferScope, paymentId)
                return@vtx ScreeningDecision.CLEAR
            }
            else -> Unit
        }

        val results = try {
            listOf(
                screeningPort.screen(payment.debtorName, ScreeningRole.DEBTOR, "$paymentId:debtor"),
                screeningPort.screen(payment.creditorName, ScreeningRole.CREDITOR, "$paymentId:creditor"),
            )
        } catch (ex: ScreeningUnavailableException) {
            log.warnf(ex, "Sanctions screening unavailable for payment %s; returning REVIEW", paymentId)
            openCaseQuietly(payment, AmlCaseRiskLevel.MEDIUM, ALERT_SCREENING_UNAVAILABLE, ex.message, null)
            return@vtx ScreeningDecision.REVIEW
        }

        val decision = applySddPolicy(payment, ScreeningPolicy.decide(results), paymentId)
        if (decision == ScreeningDecision.BLOCK || decision == ScreeningDecision.REVIEW) {
            openSanctionsCase(payment, results, decision)
        }
        decision
    }

    /** SDD for INTERNAL_CLIENT: bank holds full KYC on both parties — POTENTIAL_HIT is not
     *  actionable and must not escalate to REVIEW hold (AMLD4 Art. 15–17). */
    private fun applySddPolicy(payment: DomesticPayment, raw: ScreeningDecision, paymentId: UUID): ScreeningDecision =
        if (payment.transferScope == DomesticTransferScope.INTERNAL_CLIENT && raw == ScreeningDecision.REVIEW) {
            log.infof("INTERNAL_CLIENT payment %s: downgrading REVIEW → CLEAR under SDD", paymentId)
            ScreeningDecision.CLEAR
        } else {
            raw
        }

    private suspend fun openSanctionsCase(
        payment: DomesticPayment,
        results: List<ScreeningResult>,
        decision: ScreeningDecision,
    ) {
        val nonClear = results.filter {
            it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
        }
        val detail = nonClear
            .joinToString("; ") { "${it.role} '${it.subject}' ${it.status} score=${it.score}" }
            .ifBlank { "no actionable matches" }
        val riskLevel = if (decision == ScreeningDecision.BLOCK) AmlCaseRiskLevel.CRITICAL else AmlCaseRiskLevel.HIGH
        openCaseQuietly(
            payment,
            riskLevel,
            ALERT_SANCTIONS_HIT,
            detail,
            nonClear.firstNotNullOfOrNull {
                it.matchedEntity
            },
        )
    }

    override fun validatePayment(paymentId: UUID): Unit = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during validate activity")
        // RECEIVED is the only status that may become VALIDATED, so on a payment that already
        // moved past this step there is nothing to do. Without the early return a re-drive of a
        // stranded payment dies here on "Invalid domestic payment status transition" and never
        // reaches settlePayment — which is the step that would actually recover it, and which is
        // idempotent (payment-scoped key, 409 = already booked). submitScheme and settlePayment
        // below both carry the same guard; validate was the one activity that did not (#4182).
        if (payment.status != DomesticPaymentStatus.RECEIVED) return@vtx Unit
        val updated = payment.transitionTo(DomesticPaymentStatus.VALIDATED, clock = clock)
        paymentRepository.update(
            payment = updated,
            outboxMessage = OutboxMessage(
                aggregateId = updated.id,
                eventType = PAYMENT_STATUS_CHANGED_EVENT,
                payload = eventPublisher.statusChangedPayload(payment, updated),
            ),
        )
        Unit
    }

    override fun rejectPayment(paymentId: UUID): Unit = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during reject activity")
        val updated = payment.transitionTo(
            targetStatus = DomesticPaymentStatus.REJECTED,
            reason = DomesticRejectReason.SANCTIONS_HIT,
            clock = clock,
        )
        paymentRepository.update(
            payment = updated,
            outboxMessage = OutboxMessage(
                aggregateId = updated.id,
                eventType = PAYMENT_STATUS_CHANGED_EVENT,
                payload = eventPublisher.statusChangedPayload(payment, updated),
            ),
        )
        Unit
    }

    override fun shadowFraudScore(paymentId: UUID): Unit = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during fraud score activity")
        val outcome = fraudScoringPort.score(
            FraudScoreCommand(
                amount = payment.amount,
                currency = payment.currency,
                rail = "DOMESTIC",
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
                paymentId,
            )
        }
        Unit
    }

    @Suppress("TooGenericExceptionCaught")
    override fun submitScheme(paymentId: UUID): DomesticPaymentStatus = vtx {
        if (!schemeSubmissionEnabled) return@vtx DomesticPaymentStatus.VALIDATED
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during submitScheme activity")
        if (payment.status != DomesticPaymentStatus.VALIDATED) return@vtx payment.status
        try {
            val outcome = schemeGatewayPort.submit(payment)
            val (nextStatus, reason) = if (outcome.accepted) {
                DomesticPaymentStatus.SENT_TO_CLEARING to null
            } else {
                val r = when (outcome.reasonCode) {
                    "AC04", "AC06" -> DomesticRejectReason.BENEFICIARY_ACCOUNT_CLOSED
                    "RC01" -> DomesticRejectReason.INVALID_BANK_CODE
                    "AM05" -> DomesticRejectReason.INSUFFICIENT_FUNDS
                    else -> DomesticRejectReason.TECHNICAL_ERROR
                }
                DomesticPaymentStatus.REJECTED to r
            }
            val rejectDetail = if (nextStatus == DomesticPaymentStatus.REJECTED) {
                "scheme reject: ${outcome.reasonCode}"
            } else {
                null
            }
            val updated = payment.transitionTo(nextStatus, reason, rejectDetail, clock = clock)
            paymentRepository.update(
                payment = updated,
                outboxMessage = OutboxMessage(
                    aggregateId = updated.id,
                    eventType = PAYMENT_STATUS_CHANGED_EVENT,
                    payload = eventPublisher.statusChangedPayload(payment, updated),
                ),
            )
            nextStatus
        } catch (ex: SchemeGatewayUnavailableException) {
            log.warnf(ex, "Scheme gateway unavailable for payment %s; holding in VALIDATED", paymentId)
            DomesticPaymentStatus.VALIDATED
        } catch (ex: Exception) {
            log.warnf(ex, "Unexpected error during scheme submission for payment %s; holding", paymentId)
            DomesticPaymentStatus.VALIDATED
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun settlePayment(paymentId: UUID): DomesticPaymentStatus = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during settlePayment activity")
        if (payment.status != DomesticPaymentStatus.SENT_TO_CLEARING) return@vtx payment.status
        try {
            settlementPort.settle(payment)
            val updated = payment.transitionTo(DomesticPaymentStatus.SETTLED, clock = clock)
            paymentRepository.update(
                payment = updated,
                outboxMessage = OutboxMessage(
                    aggregateId = updated.id,
                    eventType = PAYMENT_STATUS_CHANGED_EVENT,
                    payload = eventPublisher.statusChangedPayload(payment, updated),
                ),
            )
            DomesticPaymentStatus.SETTLED
        } catch (ex: SettlementUnavailableException) {
            log.warnf(ex, "Settlement unavailable for payment %s; holding in SENT_TO_CLEARING", paymentId)
            DomesticPaymentStatus.SENT_TO_CLEARING
        } catch (ex: Exception) {
            log.warnf(ex, "Unexpected error during settlement for payment %s; holding in SENT_TO_CLEARING", paymentId)
            DomesticPaymentStatus.SENT_TO_CLEARING
        }
    }

    @Suppress("TooGenericExceptionCaught")
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
}
