// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.workflow

import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepa.application.port.out.AmlCasePort
import com.openbank.sepa.application.port.out.AmlCaseRiskLevel
import com.openbank.sepa.application.port.out.FraudScoreCommand
import com.openbank.sepa.application.port.out.FraudScoringPort
import com.openbank.sepa.application.port.out.FraudVerdict
import com.openbank.sepa.application.port.out.OpenAmlCaseCommand
import com.openbank.sepa.application.port.out.SanctionsScreeningPort
import com.openbank.sepa.application.port.out.SchemeGatewayPort
import com.openbank.sepa.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepa.application.port.out.ScreeningUnavailableException
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.application.port.out.SettlementPort
import com.openbank.sepa.application.port.out.SettlementUnavailableException
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaRejectReason
import com.openbank.sepa.domain.screening.ScreeningDecision
import com.openbank.sepa.domain.screening.ScreeningMatchStatus
import com.openbank.sepa.domain.screening.ScreeningPolicy
import com.openbank.sepa.domain.screening.ScreeningRole
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val PAYMENT_STATUS_CHANGED_EVENT = "sepa.payment.status-changed"
private const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
private const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"

@Suppress("LongParameterList")
@ApplicationScoped
open class SepaPaymentActivitiesImpl(
    private val paymentRepository: SepaPaymentRepository,
    private val screeningPort: SanctionsScreeningPort,
    private val amlCasePort: AmlCasePort,
    private val fraudScoringPort: FraudScoringPort,
    private val schemeGatewayPort: SchemeGatewayPort,
    private val settlementPort: SettlementPort,
    private val clock: Clock,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.sepa.scheme-submission.enabled", defaultValue = "false")
    private val schemeSubmissionEnabled: Boolean,
) : SepaPaymentActivities {

    private val log = Logger.getLogger(SepaPaymentActivitiesImpl::class.java)

    override fun screenPayment(paymentId: UUID): ScreeningDecision = runOnVertxContext {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during screening activity")

        val results = try {
            listOf(
                screeningPort.screen(payment.debtorName, ScreeningRole.DEBTOR, "$paymentId:debtor"),
                screeningPort.screen(payment.creditorName, ScreeningRole.CREDITOR, "$paymentId:creditor"),
            )
        } catch (ex: ScreeningUnavailableException) {
            log.warnf(ex, "Sanctions screening unavailable for payment %s; returning REVIEW", paymentId)
            openCaseQuietly(
                payment = payment,
                riskLevel = AmlCaseRiskLevel.MEDIUM,
                alertCode = ALERT_SCREENING_UNAVAILABLE,
                detail = ex.message,
                matchedEntity = null,
            )
            return@runOnVertxContext ScreeningDecision.REVIEW
        }

        // openbank_sanctions_screenings_total / openbank_sanctions_hits_total had NO call site
        // anywhere in this class before (issue #5049): sanctions-service itself has no "role"
        // concept of its own (its EntityType is INDIVIDUAL/ORGANIZATION/VESSEL/AIRCRAFT, an
        // orthogonal axis to debtor/creditor), so DomainMetrics.sanctionsScreening/sanctionsHit
        // can only be recorded HERE, by the caller that knows which side of the payment each
        // screened name is. One event per screened entity, not per payment -- results always
        // has exactly one entry per role (debtor, creditor).
        for (result in results) {
            metrics.sanctionsScreening(result.role.name.lowercase())
            val severity = when (result.status) {
                ScreeningMatchStatus.HIT, ScreeningMatchStatus.ESCALATED -> "block"
                ScreeningMatchStatus.POTENTIAL_HIT -> "review"
                ScreeningMatchStatus.CLEAR, ScreeningMatchStatus.WHITELISTED -> null
            }
            if (severity != null) metrics.sanctionsHit(result.role.name.lowercase(), severity)
        }

        val decision = ScreeningPolicy.decide(results)

        if (decision == ScreeningDecision.BLOCK || decision == ScreeningDecision.REVIEW) {
            val nonClear = results.filter {
                it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
            }
            val detail = nonClear
                .joinToString("; ") { "${it.role} '${it.subject}' ${it.status} score=${it.score}" }
                .ifBlank { "no actionable matches" }
            val riskLevel = if (decision == ScreeningDecision.BLOCK) {
                AmlCaseRiskLevel.CRITICAL
            } else {
                AmlCaseRiskLevel.HIGH
            }
            openCaseQuietly(
                payment = payment,
                riskLevel = riskLevel,
                alertCode = ALERT_SANCTIONS_HIT,
                detail = detail,
                matchedEntity = nonClear.firstNotNullOfOrNull { it.matchedEntity },
            )
        }

        decision
    }

    // Issue #3994/#5256: every payload below also carries `sourceService`, so AuditConsumer
    // attributes the row from the producer's own claim (AttributionSource.EVENT) rather than
    // falling through to its topic-derived table — a silent, successful default visible only by
    // grouping `audit_entries` on a live database. The fleet sweep patched only the non-Temporal
    // path (SepaPaymentEvents.kt, a serialised data class); these five hand-built payload strings
    // are the SAME topic (`openbank.sepa.payment.events`, via `events-out`) and were missed
    // because a grep for the quoted key cannot see a data class and a reader of the data class
    // cannot see these strings.
    //
    // #3914: every payload below carries `occurredAt` = the transitioned aggregate's `updatedAt`,
    // which `SepaPayment.transitionTo` stamps with `Instant.now(clock)` AT the state change. That
    // is the business event time; `SepaPaymentOutboxMessage.createdAt` next to it is the outbox
    // ROW's time and is not the same fact. Without the key, AuditConsumer.eventTime() returns null
    // and audit_entries.occurred_at records the consumer's INGEST time as business time — under
    // consumer lag or a replay, arbitrarily wrong. `Instant.toString()` is ISO-8601 with `Z`, which
    // `Instant.parse` accepts. The non-Temporal path (SepaPaymentEvents.kt) was already correct.
    override fun validatePayment(paymentId: UUID): Unit = runOnVertxContext {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during validate activity")
        val updated = payment.transitionTo(SepaPaymentStatus.VALIDATED, clock = clock)
        paymentRepository.update(
            payment = updated,
            outboxMessage = SepaPaymentOutboxMessage(
                aggregateId = updated.id,
                eventType = PAYMENT_STATUS_CHANGED_EVENT,
                payload = """{"paymentId":"$paymentId","status":"VALIDATED",""" +
                    """"occurredAt":"${updated.updatedAt}","sourceService":"$SOURCE_SERVICE"}""",
                createdAt = Instant.now(clock),
            ),
        )
        Unit
    }

    override fun rejectPayment(paymentId: UUID): Unit = runOnVertxContext {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during reject activity")
        val updated = payment.transitionTo(SepaPaymentStatus.REJECTED, SepaRejectReason.SANCTIONS_HIT, clock = clock)
        paymentRepository.update(
            payment = updated,
            outboxMessage = SepaPaymentOutboxMessage(
                aggregateId = updated.id,
                eventType = PAYMENT_STATUS_CHANGED_EVENT,
                payload = """{"paymentId":"$paymentId","status":"REJECTED","reason":"SANCTIONS_HIT",""" +
                    """"occurredAt":"${updated.updatedAt}","sourceService":"$SOURCE_SERVICE"}""",
                createdAt = Instant.now(clock),
            ),
        )
        Unit
    }

    override fun shadowFraudScore(paymentId: UUID): Unit = runOnVertxContext {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during fraud score activity")
        val outcome = fraudScoringPort.score(
            FraudScoreCommand(
                amount = payment.amount,
                currency = payment.currency,
                rail = "SEPA",
                accountId = payment.debtorAccountId,
                counterpartyId = null,
            ),
        )
        if (outcome.synthetic) {
            // #4221: a payment that was never scored is not a payment that scored clean.
            log.warnf(
                "Fraud scoring UNAVAILABLE for payment %s — synthetic ALLOW, this payment carries no " +
                    "fraud verdict (see openbank_fraud_scoring_degraded{service=\"sepa-payment\"})",
                paymentId,
            )
        } else if (outcome.verdict != FraudVerdict.ALLOW) {
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

    override fun submitToScheme(paymentId: UUID): SepaPaymentStatus = runOnVertxContext {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during scheme-submission activity")
        if (!schemeSubmissionEnabled || payment.status != SepaPaymentStatus.VALIDATED) {
            return@runOnVertxContext payment.status
        }

        val outcome = try {
            schemeGatewayPort.submit(payment)
        } catch (ex: SchemeGatewayUnavailableException) {
            log.warnf(ex, "Scheme gateway unavailable for payment %s; holding in VALIDATED", paymentId)
            return@runOnVertxContext SepaPaymentStatus.VALIDATED
        }

        if (outcome.accepted) {
            return@runOnVertxContext settleAfterAcceptance(payment, paymentId)
        } else {
            val rejected = payment.transitionTo(
                SepaPaymentStatus.REJECTED,
                mapSchemeReason(outcome.reasonCode),
                "scheme reject (pacs.002): ${outcome.reasonCode ?: "unspecified"}",
                clock = clock,
            )
            paymentRepository.update(
                payment = rejected,
                outboxMessage = SepaPaymentOutboxMessage(
                    aggregateId = rejected.id,
                    eventType = PAYMENT_STATUS_CHANGED_EVENT,
                    payload = """{"paymentId":"$paymentId","status":"REJECTED",""" +
                        """"occurredAt":"${rejected.updatedAt}","sourceService":"$SOURCE_SERVICE"}""",
                    createdAt = Instant.now(clock),
                ),
            )
            return@runOnVertxContext SepaPaymentStatus.REJECTED
        }
    }

    /**
     * ADR-0108: persist PROCESSING, call transaction-service to book funds, then advance to COMPLETED.
     * Fail-open: if settlement is unavailable the payment stays in PROCESSING for retry.
     */
    private suspend fun settleAfterAcceptance(payment: SepaPayment, paymentId: UUID): SepaPaymentStatus {
        val processing = payment.transitionTo(SepaPaymentStatus.PROCESSING, clock = clock)
        paymentRepository.update(
            payment = processing,
            outboxMessage = SepaPaymentOutboxMessage(
                aggregateId = processing.id,
                eventType = PAYMENT_STATUS_CHANGED_EVENT,
                payload = """{"paymentId":"$paymentId","status":"PROCESSING",""" +
                    """"occurredAt":"${processing.updatedAt}","sourceService":"$SOURCE_SERVICE"}""",
                createdAt = Instant.now(clock),
            ),
        )
        val settled = try {
            settlementPort.settle(processing)
        } catch (ex: SettlementUnavailableException) {
            log.warnf(ex, "Settlement unavailable for payment %s; holding in PROCESSING", paymentId)
            return SepaPaymentStatus.PROCESSING
        }
        return if (settled.settled) {
            val completed = processing.transitionTo(SepaPaymentStatus.COMPLETED, clock = clock)
            paymentRepository.update(
                payment = completed,
                outboxMessage = SepaPaymentOutboxMessage(
                    aggregateId = completed.id,
                    eventType = PAYMENT_STATUS_CHANGED_EVENT,
                    payload = """{"paymentId":"$paymentId","status":"COMPLETED",""" +
                        """"occurredAt":"${completed.updatedAt}","sourceService":"$SOURCE_SERVICE"}""",
                    createdAt = Instant.now(clock),
                ),
            )
            SepaPaymentStatus.COMPLETED
        } else {
            log.warnf("Settlement returned not-settled for payment %s; holding in PROCESSING", paymentId)
            SepaPaymentStatus.PROCESSING
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

    @Suppress("TooGenericExceptionCaught")
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

    /**
     * Run a reactive (Hibernate Reactive / Mutiny) suspend [block] on a Vert.x duplicated context.
     *
     * Temporal activity methods are dispatched on a Temporal worker thread, which has **no** current
     * Vert.x context. A naive `runBlocking { panache.withSession { ... } }` therefore fails with
     * `IllegalStateException: No current Vertx context found` — the reactive Panache session cannot be
     * opened. (Surfaced by the ADR-0104 D3 sandbox e2e: every SEPA payment stuck at RECEIVED because
     * the `ScreenPayment` activity could not query the DB.)
     *
     * [VertxContextSupport.subscribeAndAwait] establishes a fresh duplicated context, runs the supplier
     * and subscribes the resulting [io.smallrye.mutiny.Uni] on it, then blocks the worker thread until it
     * completes — the Temporal-activity equivalent of the request thread's ambient context. The suspend
     * [block] is bridged to a `Uni` via mutiny-kotlin's [asUni]; `Dispatchers.Unconfined` keeps it on the
     * duplicated-context thread until the first real suspension, so the reactive session binds correctly.
     */
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}

/**
 * This service's audit attribution (issue #3994/#5256) — the module directory without the
 * `openbank-` prefix, matching `SepaPaymentEvents.kt`'s non-Temporal payloads and the value
 * audit-service's own topic table maps `openbank.sepa.payment.events` to.
 */
private const val SOURCE_SERVICE = "sepa-payment"
