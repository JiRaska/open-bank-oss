// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.application.usecase

import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepainstant.application.port.`in`.GetSctInstPaymentUseCase
import com.openbank.sepainstant.application.port.`in`.RecallSctInstPaymentUseCase
import com.openbank.sepainstant.application.port.`in`.SubmitSctInstCommand
import com.openbank.sepainstant.application.port.`in`.SubmitSctInstPaymentUseCase
import com.openbank.sepainstant.application.port.out.AmlCasePort
import com.openbank.sepainstant.application.port.out.AmlCaseRiskLevel
import com.openbank.sepainstant.application.port.out.FraudScoreCommand
import com.openbank.sepainstant.application.port.out.FraudScoringPort
import com.openbank.sepainstant.application.port.out.FraudVerdict
import com.openbank.sepainstant.application.port.out.OpenAmlCaseCommand
import com.openbank.sepainstant.application.port.out.SanctionsScreeningPort
import com.openbank.sepainstant.application.port.out.SchemeGatewayPort
import com.openbank.sepainstant.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepainstant.application.port.out.ScreeningUnavailableException
import com.openbank.sepainstant.application.port.out.SctInstEventPublisher
import com.openbank.sepainstant.application.port.out.SctInstPaymentRepository
import com.openbank.sepainstant.application.port.out.SettlementPort
import com.openbank.sepainstant.application.port.out.SettlementUnavailableException
import com.openbank.sepainstant.domain.event.SctInstPaymentRecalled
import com.openbank.sepainstant.domain.event.SctInstPaymentRejected
import com.openbank.sepainstant.domain.event.SctInstPaymentSettled
import com.openbank.sepainstant.domain.event.SctInstPaymentSubmitted
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.sepainstant.domain.screening.ScreeningDecision
import com.openbank.sepainstant.domain.screening.ScreeningMatchStatus
import com.openbank.sepainstant.domain.screening.ScreeningPolicy
import com.openbank.sepainstant.domain.screening.ScreeningResult
import com.openbank.sepainstant.domain.screening.ScreeningRole
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Suppress("LongParameterList")
@ApplicationScoped
class SctInstPaymentService(
    private val repo: SctInstPaymentRepository,
    private val publisher: SctInstEventPublisher,
    private val screeningPort: SanctionsScreeningPort,
    private val amlCasePort: AmlCasePort,
    private val fraudScoringPort: FraudScoringPort,
    private val schemeGatewayPort: SchemeGatewayPort,
    private val settlementPort: SettlementPort,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.sct-inst.execution-timeout-seconds", defaultValue = "10")
    private val timeoutSeconds: Long,
    @ConfigProperty(name = "openbank.sct-inst.scheme-submission.enabled", defaultValue = "false")
    private val schemeSubmissionEnabled: Boolean,
    private val clock: Clock,
) : SubmitSctInstPaymentUseCase,
    GetSctInstPaymentUseCase,
    RecallSctInstPaymentUseCase {

    @Inject
    constructor(
        repo: SctInstPaymentRepository,
        publisher: SctInstEventPublisher,
        screeningPort: SanctionsScreeningPort,
        amlCasePort: AmlCasePort,
        fraudScoringPort: FraudScoringPort,
        schemeGatewayPort: SchemeGatewayPort,
        settlementPort: SettlementPort,
        metrics: DomainMetrics,
        @ConfigProperty(name = "openbank.sct-inst.execution-timeout-seconds", defaultValue = "10")
        timeoutSeconds: Long,
        @ConfigProperty(name = "openbank.sct-inst.scheme-submission.enabled", defaultValue = "false")
        schemeSubmissionEnabled: Boolean,
    ) : this(
        repo,
        publisher,
        screeningPort,
        amlCasePort,
        fraudScoringPort,
        schemeGatewayPort,
        settlementPort,
        metrics,
        timeoutSeconds,
        schemeSubmissionEnabled,
        Clock.systemUTC(),
    )

    private val log = Logger.getLogger(SctInstPaymentService::class.java)

    private companion object {
        const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
        const val ALERT_AML_HOLD = "AML_HOLD"
        const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"
        const val ALERT_SCHEME_UNAVAILABLE = "SCHEME_UNAVAILABLE"
        const val ALERT_SETTLEMENT_UNAVAILABLE = "SETTLEMENT_UNAVAILABLE"
    }

    override fun submit(command: SubmitSctInstCommand): Uni<SctInstPayment> =
        repo.findByIdempotencyKey(command.idempotencyKey)
            .flatMap { existing ->
                if (existing != null) {
                    Uni.createFrom().item(existing)
                } else {
                    // ADR-0084 §4.1 (SHADOW): score after screening; fail-open, never blocks/holds.
                    applyScreening(command).call { payment -> scoreFraudShadow(payment) }
                }
            }

    /**
     * Fraud scoring in SHADOW mode (ADR-0084 §1/§4.1): observe the verdict, never enforce it.
     * A non-ALLOW verdict is logged for the RTS Art. 18 baseline; ALLOW is silent. Uni<>-native
     * (matches sepa-instant's reactive contract); adapter is fail-open so errors are swallowed here.
     */
    private fun scoreFraudShadow(payment: SctInstPayment): Uni<Void> = fraudScoringPort.score(
        FraudScoreCommand(
            amount = payment.amount,
            currency = payment.currency,
            rail = "SCT_INST",
            accountId = payment.debtorAccountId,
            counterpartyId = null,
        ),
    )
        .invoke { outcome ->
            if (outcome.synthetic) {
                // #4221: a payment that was never scored is not a payment that scored clean.
                log.warnf(
                    "Fraud scoring UNAVAILABLE for payment %s — synthetic ALLOW, this payment carries no " +
                        "fraud verdict (see openbank_fraud_scoring_degraded{service=\"sepa-instant\"})",
                    payment.paymentId,
                )
            } else if (outcome.verdict != FraudVerdict.ALLOW) {
                log.infof(
                    "Fraud SHADOW verdict %s (score=%d, rules=%s) for payment %s — observed, not enforced",
                    outcome.verdict,
                    outcome.score,
                    outcome.ruleVersion,
                    payment.paymentId,
                )
            }
        }
        .replaceWithVoid()
        .onFailure().invoke { ex ->
            log.warnf(ex, "Fraud shadow error for payment %s (fail-open)", payment.paymentId)
        }
        .onFailure().recoverWithUni { _: Throwable -> Uni.createFrom().voidItem() }

    /**
     * ADR-0032 §B, adapted for the instant rail: an SCT Inst payment settles within seconds, so the
     * sanctions screening is the gate at submission time. A CLEAR result lets the payment proceed
     * (PROCESSING + Submitted); a sanctions hit is REJECTED (SANCTIONS_HIT); a sub-threshold potential
     * hit is held PENDING for review; and a screening outage fails closed — held PENDING, never
     * released (§C). The held/rejected record is persisted so it is never lost.
     */
    private fun applyScreening(command: SubmitSctInstCommand): Uni<SctInstPayment> {
        val base = SctInstPayment(
            idempotencyKey = command.idempotencyKey,
            status = SctInstStatus.PENDING,
            debtorAccountId = command.debtorAccountId,
            debtorIban = command.debtorIban,
            debtorName = command.debtorName,
            creditorIban = command.creditorIban,
            creditorName = command.creditorName,
            creditorBic = command.creditorBic,
            amount = command.amount,
            currency = command.currency,
            remittanceInfo = command.remittanceInfo,
            endToEndId = command.endToEndId,
            executionTimeoutAt = null,
            settledAt = null,
            recalledAt = null,
            recallReason = null,
            rejectReason = null,
            rejectDetail = null,
            submittedAt = OffsetDateTime.now(clock),
            createdAt = OffsetDateTime.now(clock),
            updatedAt = OffsetDateTime.now(clock),
        )
        metrics.paymentSubmitted("sepa_instant", base.currency)
        return screeningPort.screen(base.debtorName, ScreeningRole.DEBTOR, "${base.paymentId}:debtor")
            .flatMap { debtor ->
                screeningPort.screen(base.creditorName, ScreeningRole.CREDITOR, "${base.paymentId}:creditor")
                    .map { creditor -> listOf(debtor, creditor) }
            }
            .flatMap { results -> applyDecision(base, results) }
            .onFailure(ScreeningUnavailableException::class.java).recoverWithUni { ex ->
                log.warnf(ex, "Sanctions screening unavailable for payment %s; holding in PENDING", base.paymentId)
                hold(base, AmlCaseRiskLevel.MEDIUM, ALERT_SCREENING_UNAVAILABLE, ex.message, null)
            }
    }

    private fun applyDecision(base: SctInstPayment, results: List<ScreeningResult>): Uni<SctInstPayment> {
        results.forEach { metrics.sanctionsScreening(it.role.name.lowercase()) }
        return when (ScreeningPolicy.decide(results)) {
            ScreeningDecision.CLEAR -> submitToScheme(base)
            ScreeningDecision.REVIEW ->
                hold(base, AmlCaseRiskLevel.HIGH, ALERT_AML_HOLD, detail(results), matchedEntity(results))
                    .invoke { _ ->
                        results.filter {
                            it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
                        }.forEach { metrics.sanctionsHit(it.role.name.lowercase(), "review") }
                    }
            ScreeningDecision.BLOCK -> reject(base, detail(results), matchedEntity(results))
        }
    }

    /**
     * ADR-0104 D4 + ADR-0108: once screened CLEAR, build a real pacs.008 and submit it to the
     * scheme gateway, acting on the pacs.002 verdict — `ACSC` → [proceed] to PROCESSING, then
     * immediately call [settlementPort] to book the funds and transition to SETTLED. `RJCT` →
     * reject with the scheme reason. Fails **closed**: an unreachable gateway holds the payment
     * PENDING; an unreachable transaction-service leaves it PROCESSING (funds not yet booked).
     * Flag-gated — default OFF preserves today's behaviour exactly.
     */
    private fun submitToScheme(base: SctInstPayment): Uni<SctInstPayment> {
        if (!schemeSubmissionEnabled) return proceed(base)
        return schemeGatewayPort.submit(base)
            .flatMap { outcome ->
                if (outcome.accepted) {
                    proceed(base).flatMap { processing -> settleAfterScheme(processing) }
                } else {
                    rejectScheme(base, outcome.reasonCode)
                }
            }
            .onFailure(SchemeGatewayUnavailableException::class.java).recoverWithUni { ex ->
                log.warnf(ex, "Scheme gateway unavailable for instant payment %s; holding", base.paymentId)
                hold(base, AmlCaseRiskLevel.MEDIUM, ALERT_SCHEME_UNAVAILABLE, ex.message, null)
            }
    }

    /**
     * ADR-0108: call transaction-service to book the funds after ACSC and transition to SETTLED.
     * Fails **open** for the settlement step only — an unreachable transaction-service leaves the
     * payment in PROCESSING so a reconciliation job can retry; it does NOT hold/reject.
     */
    private fun settleAfterScheme(processing: SctInstPayment): Uni<SctInstPayment> = settlementPort.settle(processing)
        .flatMap { outcome ->
            if (outcome.settled) {
                complete(processing)
            } else {
                log.warnf(
                    "Settlement returned settled=false for instant payment %s; leaving in PROCESSING",
                    processing.paymentId,
                )
                Uni.createFrom().item(processing)
            }
        }
        .onFailure(SettlementUnavailableException::class.java).recoverWithUni { ex ->
            log.warnf(
                ex,
                "Settlement unavailable for instant payment %s; leaving in PROCESSING for reconciliation",
                processing.paymentId,
            )
            Uni.createFrom().item(processing)
        }

    /** RJCT from the scheme: persist REJECTED with the ISO 20022 reason and emit Rejected. */
    private fun rejectScheme(base: SctInstPayment, reasonCode: String?): Uni<SctInstPayment> {
        val code = reasonCode ?: "RJCT"
        val rejected = base.copy(
            status = SctInstStatus.REJECTED,
            rejectReason = code,
            rejectDetail = "scheme reject (pacs.002): $code",
        )
        return repo.save(rejected).flatMap { saved ->
            publisher.publish(
                SctInstPaymentRejected(
                    paymentId = saved.paymentId,
                    reason = code,
                    occurredAt = OffsetDateTime.now(clock),
                ),
            )
                .invoke { _ -> metrics.paymentCompleted("sepa_instant", saved.currency, "rejected") }
                .replaceWith(saved)
        }
    }

    /** CLEAR: release the instant payment (PROCESSING + Submitted), arming the execution timeout. */
    private fun proceed(base: SctInstPayment): Uni<SctInstPayment> {
        val processing = base.copy(
            status = SctInstStatus.PROCESSING,
            executionTimeoutAt = OffsetDateTime.now(clock).plusSeconds(timeoutSeconds),
        )
        return repo.save(processing).flatMap { saved ->
            publisher.publish(
                SctInstPaymentSubmitted(
                    paymentId = saved.paymentId,
                    debtorIban = saved.debtorIban,
                    creditorIban = saved.creditorIban,
                    amount = saved.amount,
                    currency = saved.currency,
                    endToEndId = saved.endToEndId,
                    occurredAt = OffsetDateTime.now(clock),
                ),
            ).map { saved }
        }
    }

    /** ADR-0108: funds booked — transition from PROCESSING to SETTLED and emit Settled. */
    private fun complete(processing: SctInstPayment): Uni<SctInstPayment> {
        val now = OffsetDateTime.now(clock)
        val settled = processing.copy(status = SctInstStatus.SETTLED, settledAt = now)
        return repo.save(settled).flatMap { saved ->
            publisher.publish(SctInstPaymentSettled(paymentId = saved.paymentId, settledAt = now, occurredAt = now))
                .invoke { _ -> metrics.paymentCompleted("sepa_instant", saved.currency, "settled") }
                .replaceWith(saved)
        }
    }

    /** BLOCK: persist REJECTED (SANCTIONS_HIT), open a CRITICAL AML case, and emit Rejected. */
    private fun reject(base: SctInstPayment, detail: String, matched: String?): Uni<SctInstPayment> {
        val rejected = base.copy(
            status = SctInstStatus.REJECTED,
            rejectReason = ALERT_SANCTIONS_HIT,
            rejectDetail = detail,
        )
        return repo.save(rejected).flatMap { saved ->
            openCaseQuietly(saved, AmlCaseRiskLevel.CRITICAL, ALERT_SANCTIONS_HIT, detail, matched)
                .flatMap {
                    publisher.publish(
                        SctInstPaymentRejected(
                            paymentId = saved.paymentId,
                            reason = ALERT_SANCTIONS_HIT,
                            occurredAt = OffsetDateTime.now(clock),
                        ),
                    )
                }
                .invoke { _ ->
                    metrics.paymentCompleted("sepa_instant", saved.currency, "rejected")
                    metrics.sanctionsHit("debtor", "block")
                }
                .replaceWith(saved)
        }
    }

    /** REVIEW / screening-unavailable: park the payment PENDING for human handling, never settle it. */
    private fun hold(
        base: SctInstPayment,
        risk: AmlCaseRiskLevel,
        alert: String,
        detail: String?,
        matched: String?,
    ): Uni<SctInstPayment> {
        val held = base.copy(status = SctInstStatus.PENDING)
        return repo.save(held).flatMap { saved ->
            openCaseQuietly(saved, risk, alert, detail, matched).replaceWith(saved)
        }
    }

    /** Opening the AML case is best-effort: a case-store outage must not flip the screening verdict. */
    private fun openCaseQuietly(
        payment: SctInstPayment,
        risk: AmlCaseRiskLevel,
        alert: String,
        detail: String?,
        matched: String?,
    ): Uni<Void> = amlCasePort.openCase(
        OpenAmlCaseCommand(
            idempotencyKey = "aml-${payment.paymentId}-$alert",
            paymentId = payment.paymentId,
            debtorAccountId = payment.debtorAccountId,
            customerReference = "${payment.debtorName} / ${payment.debtorIban}",
            riskLevel = risk,
            alertCode = alert,
            alertDetail = detail,
            matchedEntity = matched,
        ),
    ).onFailure().recoverWithUni { ex ->
        log.errorf(ex, "Failed to open AML case (%s) for payment %s", alert, payment.paymentId)
        Uni.createFrom().voidItem()
    }

    private fun detail(results: List<ScreeningResult>): String =
        results.filter { it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED }
            .joinToString("; ") { "${it.role} '${it.subject}' ${it.status} score=${it.score}" }
            .ifBlank { "no actionable matches" }

    private fun matchedEntity(results: List<ScreeningResult>): String? =
        results.firstNotNullOfOrNull { it.matchedEntity }

    override fun getById(paymentId: UUID): Uni<SctInstPayment> = repo.findByPaymentId(paymentId)
        .map { it ?: throw jakarta.ws.rs.NotFoundException("Payment $paymentId not found") }

    override fun listAll(): Uni<List<SctInstPayment>> = repo.findAll()

    override fun listByDebtor(debtorAccountId: UUID, page: Int, size: Int): Uni<List<SctInstPayment>> =
        repo.findByDebtorAccountId(debtorAccountId, page, size)

    override fun recall(paymentId: UUID, reason: String): Uni<SctInstPayment> = repo.findByPaymentId(paymentId)
        .flatMap { payment ->
            val p = payment ?: throw jakarta.ws.rs.NotFoundException("Payment $paymentId not found")
            if (p.status != SctInstStatus.SETTLED) {
                throw jakarta.ws.rs.BadRequestException("Only SETTLED payments can be recalled")
            }
            val recalledAt = OffsetDateTime.now(clock)
            repo.updateStatus(paymentId, SctInstStatus.RECALLED)
                .flatMap {
                    publisher.publish(
                        SctInstPaymentRecalled(
                            paymentId = paymentId,
                            recallReason = reason,
                            occurredAt = recalledAt,
                        ),
                    )
                }
                .map {
                    p.copy(
                        status = SctInstStatus.RECALLED,
                        recalledAt = recalledAt,
                        recallReason = reason,
                    )
                }
        }
}
