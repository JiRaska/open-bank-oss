// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.AmlCasePort
import com.openbank.domestic.application.port.out.AmlCaseRiskLevel
import com.openbank.domestic.application.port.out.CustomerNotificationPort
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
import com.openbank.domestic.application.port.out.customerSafeReason
import com.openbank.domestic.domain.model.DomesticPayment
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

private const val PAYMENT_STATUS_CHANGED_EVENT = "domestic.payment.status-changed"
private const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
private const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"

// TooManyFunctions: one over detekt's threshold since #8432 added the private notification helper.
// The activities are a Temporal contract — one method per workflow step — so splitting the class to
// satisfy a count would put steps of one workflow in two places.
@Suppress("LongParameterList", "TooManyFunctions")
@ApplicationScoped
open class DomesticPaymentActivitiesImpl(
    private val paymentRepository: DomesticPaymentRepository,
    private val eventPublisher: DomesticPaymentEventPublisher,
    private val screeningPort: SanctionsScreeningPort,
    private val amlCasePort: AmlCasePort,
    private val fraudScoringPort: FraudScoringPort,
    private val schemeGatewayPort: SchemeGatewayPort,
    private val settlementPort: SettlementPort,
    private val accountLookupPort: AccountLookupPort,
    private val customerNotificationPort: CustomerNotificationPort,
    private val clock: Clock,
    private val metrics: DomainMetrics,
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

        // Issue #5049: openbank_sanctions_screenings_total / openbank_sanctions_hits_total had NO
        // call site anywhere in this class -- see SepaPaymentActivitiesImpl.screenPayment for why
        // sanctions-service itself cannot record these (no "role" concept of its own).
        for (result in results) {
            metrics.sanctionsScreening(result.role.name.lowercase())
            val severity = when (result.status) {
                ScreeningMatchStatus.HIT, ScreeningMatchStatus.ESCALATED -> "block"
                ScreeningMatchStatus.POTENTIAL_HIT -> "review"
                ScreeningMatchStatus.CLEAR, ScreeningMatchStatus.WHITELISTED -> null
            }
            if (severity != null) metrics.sanctionsHit(result.role.name.lowercase(), severity)
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
        // idempotent (payment-scoped key; a replay returns the existing transaction). submitScheme
        // and settlePayment below both carry the same guard; validate was the one that did not (#4182).
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

    /**
     * Tell the owner their payment did not go — best effort, and only for a SCHEME rejection.
     *
     * **Never called from [rejectPayment].** That activity is the sanctions-screening BLOCK path
     * and always records [DomesticRejectReason.SANCTIONS_HIT]; telling the customer their payment
     * was stopped by a financial-crime control is tipping-off. Whether they should instead receive
     * a neutral "we could not complete this, please contact us" is a compliance decision, not a
     * mapping — see #8432. [customerSafeReason] would render those three reasons harmlessly
     * anyway, but the guard that matters is this call site, not the string.
     *
     * The recipient is the account OWNER, not [DomesticPayment.actorId]: for a delegated payment
     * the actor is the dispositor, and it is the owner whose money did not move.
     *
     * Failure is swallowed. A notification that cannot be published must never fail the activity —
     * Temporal would retry it, and the retry would re-run bookkeeping for a verdict already
     * recorded.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun notifyPaymentFailed(payment: DomesticPayment, reason: DomesticRejectReason?) {
        try {
            val ownerPartyId = accountLookupPort.findPartyByAccountId(payment.debtorAccountId)
            if (ownerPartyId == null) {
                log.warnf(
                    "Payment %s was rejected but its account owner could not be resolved — " +
                        "the customer will not be told (#8432)",
                    payment.id,
                )
                return
            }
            customerNotificationPort.notifyPaymentFailed(
                partyId = ownerPartyId,
                amount = payment.amount,
                currency = payment.currency,
                reason = customerSafeReason(reason),
            )
        } catch (e: Exception) {
            log.warnf(e, "Payment %s rejection recorded but the customer notification failed (#8432)", payment.id)
        }
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
        if (outcome.synthetic) {
            // #4221: the fail-open fallback used to land here as an ordinary ALLOW and say nothing.
            // A payment that was never scored is not a payment that scored clean, and this is the
            // only place that difference is recorded per payment.
            log.warnf(
                "Fraud scoring UNAVAILABLE for payment %s — synthetic ALLOW, this payment carries no " +
                    "fraud verdict (see openbank_fraud_scoring_degraded{service=\"domestic\"})",
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

    override fun submitScheme(paymentId: UUID): DomesticPaymentStatus = vtx {
        if (!schemeSubmissionEnabled) return@vtx DomesticPaymentStatus.VALIDATED
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during submitScheme activity")
        if (payment.status != DomesticPaymentStatus.VALIDATED) return@vtx payment.status

        // #4218. VALIDATED alone does not mean "not yet submitted": until this guard existed, a
        // failure of the status write AFTER a successful submit was caught below and logged as
        // "holding in VALIDATED", leaving a live clearing item behind a row that claimed nothing
        // was sent. A re-drive then read that row and submitted a SECOND clearing item for one
        // payment — nothing downstream dedups it (no idempotency key on the pacs.008, and
        // clearing-simulator does not dedup at all). The marker is what tells the two states apart.
        //
        // Held rather than progressed on purpose: we know a pacs.008 went out, not what the scheme
        // said about it, and guessing either way is worse than stopping. Recovering it is an
        // operator action against the scheme's own record — see the index this migration adds.
        // The claim IS the guard, and it has to be one statement. Reading `schemeDispatchedAt`
        // here and writing it below would leave a window where two attempts both see null, both
        // pass, and both submit — the duplicate clearing item this whole change exists to prevent.
        // The database arbitrates; `false` means we lost the race and must not send anything.
        //
        // Claimed BEFORE the call, so it survives any failure after it. Held rather than progressed
        // on purpose: we know a pacs.008 went out, not what the scheme said about it, and guessing
        // either way is worse than stopping. Recovering it is an operator action against the
        // scheme's own record — see the index the migration adds.
        if (!paymentRepository.claimSchemeDispatch(paymentId, Instant.now(clock))) {
            log.errorf(
                "Payment %s is VALIDATED but the scheme dispatch is already claimed — " +
                    "NOT re-submitting (#4218). A clearing item may exist without a recorded " +
                    "outcome; reconcile against the scheme before releasing this payment.",
                paymentId,
            )
            return@vtx DomesticPaymentStatus.VALIDATED
        }

        // The catch covers the GATEWAY CALL ONLY (#4218). It used to wrap the status write below as
        // well, which is what merged "never submitted" with "submitted, bookkeeping failed".
        val outcome = try {
            schemeGatewayPort.submit(payment)
        } catch (ex: SchemeGatewayUnavailableException) {
            // Ordinary "scheme is down": the gateway proved the request never left, so this payment
            // has no clearing item and must stay re-drivable. Anything ambiguous keeps the marker
            // and therefore stops here for good — the deliberate trade of a visible strand against
            // a duplicate payment.
            if (ex.requestLeftThisProcess) {
                log.ambiguousDispatch(paymentId, ex)
            } else {
                paymentRepository.clearSchemeDispatch(paymentId)
                log.warnf(ex, "Scheme gateway unreachable for payment %s; holding in VALIDATED", paymentId)
            }
            return@vtx DomesticPaymentStatus.VALIDATED
        } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
            // SchemeGatewayPort's contract is fail-closed, so reaching here means the adapter itself
            // broke its contract. That tells us nothing about whether the pacs.008 was delivered, so
            // it is the ambiguous case: hold, keep the marker, never re-submit.
            log.ambiguousDispatch(paymentId, ex)
            return@vtx DomesticPaymentStatus.VALIDATED
        }

        // Everything below is local bookkeeping of a verdict we already hold. It is deliberately
        // NOT inside a catch: swallowing a failure here is exactly what merged "never submitted"
        // with "submitted, bookkeeping failed". Let it propagate — Temporal retries the activity,
        // and the marker above makes that retry safe.
        run {
            val (nextStatus, reason) = if (outcome.accepted) {
                DomesticPaymentStatus.SENT_TO_CLEARING to null
            } else {
                DomesticPaymentStatus.REJECTED to rejectReasonFor(outcome.reasonCode)
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
            if (nextStatus == DomesticPaymentStatus.REJECTED) {
                notifyPaymentFailed(updated, reason)
            }
            nextStatus
        }
    }

    /**
     * Book the funds for a payment the scheme has accepted (ADR-0108).
     *
     * This activity FAILS on a settlement fault instead of returning a status (#4182). Returning
     * `SENT_TO_CLEARING` here made the activity a success, and a successful activity is one
     * Temporal never retries — so the workflow completed on a non-terminal business state and the
     * payment was stranded with no timer, no retry and no reader. The configured retry policy
     * existed the whole time; nothing could ever reach it.
     *
     * Retrying is safe, and that is established from the code rather than assumed:
     *  - [SettlementAdapter][com.openbank.domestic.infrastructure.client.SettlementAdapter] sends
     *    `idempotencyKey = "domestic-settlement-<paymentId>"` — payment-scoped and stable across
     *    attempts (no timestamp, no nonce), so every retry carries the key of the first attempt.
     *  - transaction-service deduplicates on that key by early-returning the existing
     *    transaction, which it answers as **201 with that transaction** — measured, not assumed:
     *    `TransactionResource` calls `Response.created(...)` unconditionally, and the only 409s in
     *    that service come from optimistic-lock and state-transition mappers. The adapter maps that
     *    arm to `SettlementOutcome(settled = true)`, so an already-booked payment is a success.
     *    (`SettlementAdapter`'s `HTTP_CONFLICT` branch is unreachable today and kept as defence.)
     *  - the `SENT_TO_CLEARING` guard above makes the activity re-entrant: once the status write
     *    lands, a retry (or an operator re-drive) returns `SETTLED` without calling the port again.
     *
     * So the failure direction is the dangerous one here, not the retry direction: a retry costs at
     * worst a redundant round-trip that returns the same transaction, whereas swallowing costs a
     * customer a payment that left and never arrived.
     */
    override fun settlePayment(paymentId: UUID): DomesticPaymentStatus = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during settlePayment activity")
        if (payment.status != DomesticPaymentStatus.SENT_TO_CLEARING) return@vtx payment.status
        try {
            settlementPort.settle(payment)
        } catch (ex: SettlementUnavailableException) {
            // Rethrown, not absorbed: this is the planned-degradation case, and failing the
            // activity is what arms the workflow's retry policy. ERROR rather than WARN because a
            // payment that reaches here has left the bank and has not been booked.
            log.errorf(
                ex,
                "Settlement unavailable for payment %s — failing the activity so Temporal retries; " +
                    "the payment stays in SENT_TO_CLEARING and the workflow stays running (#4182)",
                paymentId,
            )
            throw ex
        }
        // Deliberately outside the catch, and with no blanket `catch (Exception)` anywhere in this
        // method. Any other fault — an invalid transition, a repository failure, an adapter bug —
        // propagates with its own type, so Temporal records what actually broke instead of
        // recording the same terminal-looking success the outage produced.
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

// Both helpers below are top-level and live AFTER the class on purpose. They are only used by
// submitScheme, and keeping them out of the class holds DomesticPaymentActivitiesImpl under
// detekt's TooManyFunctions threshold — which fires AT the limit, not above it. Placing them after
// the class also avoids the trap where a top-level declaration sitting between an annotation and
// its intended class silently steals that annotation.

/** Map the scheme's `pacs.002` reason code to the rail's reject reason (ADR-0104 D4). */
private fun rejectReasonFor(reasonCode: String?): DomesticRejectReason = when (reasonCode) {
    "AC04", "AC06" -> DomesticRejectReason.BENEFICIARY_ACCOUNT_CLOSED
    "RC01" -> DomesticRejectReason.INVALID_BANK_CODE
    "AM05" -> DomesticRejectReason.INSUFFICIENT_FUNDS
    else -> DomesticRejectReason.TECHNICAL_ERROR
}

/**
 * A dispatch whose outcome could not be established (#4218). The marker stays set, so the rail
 * refuses to submit this payment again and an operator has to reconcile it against the scheme's own
 * record. ERROR, not WARN: unlike an unreachable gateway, this one needs a human.
 */
private fun Logger.ambiguousDispatch(paymentId: UUID, ex: Throwable) = errorf(
    ex,
    "Scheme submission for payment %s failed without a usable verdict — the scheme may hold a " +
        "clearing item for it. Holding in VALIDATED and NOT re-submitting (#4218); reconcile " +
        "against the scheme before releasing this payment.",
    paymentId,
)
