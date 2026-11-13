// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.application.admission.DomesticAdmissionChain
import com.openbank.domestic.application.port.out.AmlCasePort
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.OpenAmlCaseCommand
import com.openbank.domestic.application.port.out.PaymentAdmissionMetricsPort
import com.openbank.domestic.application.port.out.PaymentAdmissionWrites
import com.openbank.domestic.application.port.out.PaymentHoldEnd
import com.openbank.domestic.domain.event.PaymentHeldEvent
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.libs.fincrime.AmlCaseSeverity
import com.openbank.libs.fincrime.admission.AdmissionDecision
import com.openbank.libs.fincrime.admission.AdmissionResult
import com.openbank.libs.fincrime.admission.AdmissionStep
import com.openbank.libs.fincrime.admission.StepOutcome
import com.openbank.libs.fincrime.admission.StepRecord
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

/**
 * Runs the ADR-0374 chain for a domestic payment and writes what it decided.
 *
 * Every write is one transaction through [PaymentAdmissionWrites]: the status, its status event
 * and, for a hold that needs a human, the `PAYMENT_HELD` event. Each method is re-entrant on the
 * payment's status, so a Temporal retry of an attempt that already committed changes nothing and
 * answers what the first attempt would have.
 */
@Suppress("LongParameterList")
@ApplicationScoped
open class PaymentAdmissionActivitiesImpl(
    private val paymentRepository: DomesticPaymentRepository,
    private val writes: PaymentAdmissionWrites,
    private val eventPublisher: DomesticPaymentEventPublisher,
    private val chain: DomesticAdmissionChain,
    private val amlCasePort: AmlCasePort,
    private val metrics: PaymentAdmissionMetricsPort,
    private val clock: Clock,
    // Read HERE, by the activity, so a workflow replay never depends on configuration. Off by
    // default: switching the chain on for this rail is its own approved release (ADR-0374).
    @ConfigProperty(name = "openbank.domestic.admission.enabled", defaultValue = "false")
    private val admissionEnabled: Boolean,
) : PaymentAdmissionActivities {

    private val log = Logger.getLogger(PaymentAdmissionActivitiesImpl::class.java)

    protected open fun <T> vtx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    override fun admit(paymentId: UUID, lastAttempt: Boolean): AdmissionAction = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during admission activity")
        when (payment.status) {
            // A payment already HELD is evaluated whatever the switch says: turning the chain off
            // must not strand the holds it placed.
            DomesticPaymentStatus.RECEIVED ->
                if (admissionEnabled) evaluate(payment, lastAttempt) else AdmissionAction.NOT_ENABLED
            DomesticPaymentStatus.HELD -> evaluate(payment, lastAttempt)
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.SENT_TO_CLEARING,
            DomesticPaymentStatus.SETTLED,
            DomesticPaymentStatus.RETURNED,
            -> AdmissionAction.ADMITTED
            DomesticPaymentStatus.REJECTED, DomesticPaymentStatus.CANCELLED -> AdmissionAction.REJECTED
        }
    }

    private suspend fun evaluate(payment: DomesticPayment, lastAttempt: Boolean): AdmissionAction {
        val result = chain.admit(payment)
        metrics.recordDecision(result.decision)
        result.steps.forEach { record ->
            val outcome = record.outcome
            if (outcome is StepOutcome.Unavailable) metrics.recordUnavailable(record.step, outcome.cause)
        }
        log.infof(
            "Admission of payment %s: %s %s (degraded=%s)",
            payment.id,
            result.decision,
            result.reasons,
            result.degraded,
        )
        return when (result.decision) {
            AdmissionDecision.ALLOW -> {
                val updated = payment.transitionTo(DomesticPaymentStatus.VALIDATED, clock = clock)
                writes.transition(updated, listOf(eventPublisher.statusChanged(payment, updated)))
                if (payment.status == DomesticPaymentStatus.HELD) {
                    metrics.recordHoldEnd(PaymentHoldEnd.CLEARED_BY_REEVALUATION)
                }
                AdmissionAction.ADMITTED
            }
            AdmissionDecision.REJECT -> rejected(payment, result)
            // No step-up exists inside an accepted domestic payment, so a CHALLENGE is a hold.
            AdmissionDecision.CHALLENGE, AdmissionDecision.HOLD -> held(payment, result, lastAttempt)
        }
    }

    private suspend fun rejected(payment: DomesticPayment, result: AdmissionResult): AdmissionAction {
        val rejecting = result.decidedBy.first().step
        val updated = payment.transitionTo(
            targetStatus = DomesticPaymentStatus.REJECTED,
            reason = rejectReasonFor(rejecting),
            clock = clock,
        )
        writes.transition(updated, listOf(eventPublisher.statusChanged(payment, updated)))
        if (payment.status == DomesticPaymentStatus.HELD) metrics.recordHoldEnd(PaymentHoldEnd.REJECTED_BY_REEVALUATION)
        if (rejecting == AdmissionStep.SANCTIONS) recordSanctionsHit(payment, result[AdmissionStep.SANCTIONS])
        // No customer notification: every reason this chain rejects for is one the customer is not
        // told (a sanctions hit, a fraud decline, a freeze that may be an AML block).
        return AdmissionAction.REJECTED
    }

    /**
     * A true review (a control looked and wants a human) requests the case at once. A hold caused
     * only by a control that could not answer does not, until [lastAttempt]: an outage must not
     * flood the review queue (ADR-0374 D13).
     */
    private suspend fun held(payment: DomesticPayment, result: AdmissionResult, lastAttempt: Boolean): AdmissionAction {
        val wantsReview = lastAttempt ||
            result.steps.any { record ->
                record.outcome is StepOutcome.Decided &&
                    (record.effective ?: AdmissionDecision.ALLOW) > AdmissionDecision.ALLOW
            }
        if (payment.status == DomesticPaymentStatus.RECEIVED) {
            val updated = payment.transitionTo(DomesticPaymentStatus.HELD, clock = clock)
            val events = buildList {
                add(eventPublisher.statusChanged(payment, updated))
                if (wantsReview) add(heldEvent(updated, result))
            }
            writes.transition(updated, events)
        } else if (wantsReview && !writes.appendWhileHeld(payment.id, heldEvent(payment, result))) {
            // It left HELD between the read and the write; the workflow's next step reads the truth.
            log.warnf("Payment %s left HELD while its review was being requested", payment.id)
        }
        return if (wantsReview) AdmissionAction.HELD_FOR_REVIEW else AdmissionAction.HELD_UNAVAILABLE
    }

    override fun releaseHeld(paymentId: UUID): Boolean = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during hold release activity")
        when (payment.status) {
            DomesticPaymentStatus.HELD -> release(payment)
            DomesticPaymentStatus.REJECTED, DomesticPaymentStatus.CANCELLED -> false
            DomesticPaymentStatus.RECEIVED -> error("Payment $paymentId is not held and cannot be released")
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.SENT_TO_CLEARING,
            DomesticPaymentStatus.SETTLED,
            DomesticPaymentStatus.RETURNED,
            -> true
        }
    }

    private suspend fun release(payment: DomesticPayment): Boolean {
        // The case decided the reason for the hold, not whether the account can still be debited.
        // Hours may have passed; the account may have been frozen since. Until a hold reserves its
        // amount, this re-run is what stands between a release and a debit of a frozen account.
        val standing = chain.standing(payment)
        val mayDebit = standing is StepOutcome.Decided && standing.decision == AdmissionDecision.ALLOW
        val updated = if (mayDebit) {
            payment.transitionTo(DomesticPaymentStatus.VALIDATED, clock = clock)
        } else {
            payment.transitionTo(
                targetStatus = DomesticPaymentStatus.REJECTED,
                reason = if (standing is StepOutcome.Decided) {
                    DomesticRejectReason.DEBTOR_ACCOUNT_BLOCKED
                } else {
                    DomesticRejectReason.TECHNICAL_ERROR
                },
                clock = clock,
            )
        }
        writes.transition(updated, listOf(eventPublisher.statusChanged(payment, updated)))
        metrics.recordHoldEnd(if (mayDebit) PaymentHoldEnd.RELEASED else PaymentHoldEnd.RELEASE_REFUSED)
        log.infof("Held payment %s released by its case: %s (standing=%s)", payment.id, updated.status, standing)
        return mayDebit
    }

    override fun rejectHeld(paymentId: UUID, cause: HoldRejectCause): Unit = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during hold reject activity")
        if (payment.status != DomesticPaymentStatus.HELD) return@vtx
        val updated = payment.transitionTo(
            targetStatus = DomesticPaymentStatus.REJECTED,
            reason = DomesticRejectReason.AML_HOLD,
            clock = clock,
        )
        writes.transition(updated, listOf(eventPublisher.statusChanged(payment, updated)))
        metrics.recordHoldEnd(
            when (cause) {
                HoldRejectCause.REVIEW_REJECTED -> PaymentHoldEnd.REVIEW_REJECTED
                HoldRejectCause.EXPIRED -> PaymentHoldEnd.EXPIRED
            },
        )
        log.infof("Held payment %s REJECTED (cause=%s)", paymentId, cause)
    }

    private fun heldEvent(held: DomesticPayment, result: AdmissionResult) = OutboxMessage(
        aggregateId = held.id,
        eventType = PaymentHeldEvent.OUTBOX_EVENT_TYPE,
        payload = eventPublisher.paymentHeldPayload(paymentHeldEvent(held, result, Instant.now(clock))),
    )

    /**
     * The record of a confirmed sanctions hit, as the screening activity kept before the chain. It
     * is not the case of a hold (a rejected payment has none), so it stays a direct, best-effort
     * call: the payment is already REJECTED and a failure here must not undo that.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun recordSanctionsHit(payment: DomesticPayment, sanctions: StepRecord) {
        val evidence = (sanctions.outcome as? StepOutcome.Decided)?.evidence?.attributes.orEmpty()
        try {
            amlCasePort.openCase(
                OpenAmlCaseCommand(
                    idempotencyKey = "aml-${payment.id}-$ALERT_SANCTIONS_HIT",
                    paymentId = payment.id,
                    debtorAccountId = payment.debtorAccountId,
                    customerReference =
                    "${payment.debtorName} / ${payment.debtorAccountNumber}/${payment.debtorBankCode}",
                    riskLevel = AmlCaseSeverity.CRITICAL,
                    alertCode = ALERT_SANCTIONS_HIT,
                    alertDetail = evidence.entries.joinToString("; ") { "${it.key}=${it.value}" },
                    matchedEntity = evidence["creditor_matched_entity"] ?: evidence["debtor_matched_entity"],
                ),
            )
        } catch (ex: Exception) {
            log.errorf(ex, "Failed to record the sanctions hit of rejected payment %s", payment.id)
        }
    }
}

private fun DomesticPaymentEventPublisher.statusChanged(previous: DomesticPayment, current: DomesticPayment) =
    OutboxMessage(
        aggregateId = current.id,
        eventType = PAYMENT_STATUS_CHANGED_EVENT,
        payload = statusChangedPayload(previous, current),
    )

/** The reject reason a refusing step maps to. Top-level, after the class, for detekt's function count. */
private fun rejectReasonFor(step: AdmissionStep): DomesticRejectReason = when (step) {
    AdmissionStep.STANDING -> DomesticRejectReason.DEBTOR_ACCOUNT_BLOCKED
    AdmissionStep.SANCTIONS -> DomesticRejectReason.SANCTIONS_HIT
    AdmissionStep.FRAUD -> DomesticRejectReason.FRAUD_SUSPECTED
    AdmissionStep.LIMITS -> DomesticRejectReason.AMOUNT_LIMIT_EXCEEDED
    AdmissionStep.AML_DESTINATION_RISK -> DomesticRejectReason.AML_HOLD
    // Unbound on this rail today; named so a step bound later cannot reject without a reason.
    AdmissionStep.AUTHORITY, AdmissionStep.PAYEE_VERIFICATION -> DomesticRejectReason.TECHNICAL_ERROR
}

/** The case request of a hold: who, why and until when, with what each control answered. */
private fun paymentHeldEvent(held: DomesticPayment, result: AdmissionResult, now: Instant): PaymentHeldEvent {
    val standing = result[AdmissionStep.STANDING].outcome
    val debtorPartyId = (standing as? StepOutcome.Decided)
        ?.evidence?.attributes?.get(DomesticAdmissionChain.EVIDENCE_DEBTOR_PARTY)
        ?.let(UUID::fromString)
    return PaymentHeldEvent(
        paymentId = held.id,
        rail = PaymentHeldEvent.RAIL,
        caseKey = PaymentHoldPolicy.caseKey(held.id),
        debtorAccountId = held.debtorAccountId,
        debtorPartyId = debtorPartyId,
        customerReference = "${held.debtorAccountNumber}/${held.debtorBankCode}",
        reasons = result.reasons.map { it.value },
        steps = result.steps.map(::heldStep),
        // The move to HELD is the last write to a held payment, so this is when the hold began.
        heldAt = held.updatedAt,
        expiresAt = held.updatedAt.plus(PaymentHoldPolicy.EXPIRY),
        occurredAt = now,
        aggregateRevision = held.aggregateRevision,
    )
}

private fun heldStep(record: StepRecord): PaymentHeldEvent.HeldStep = when (val outcome = record.outcome) {
    is StepOutcome.Decided -> PaymentHeldEvent.HeldStep(
        step = record.step.name,
        outcome = "DECIDED",
        decision = outcome.decision.name,
        reason = outcome.reason.value,
        evidence = outcome.evidence.attributes,
    )
    is StepOutcome.Unavailable -> PaymentHeldEvent.HeldStep(
        step = record.step.name,
        outcome = "UNAVAILABLE",
        decision = record.effective?.name,
        reason = outcome.cause.name,
        evidence = outcome.evidence.attributes,
    )
    is StepOutcome.NotApplicable ->
        PaymentHeldEvent.HeldStep(record.step.name, "NOT_APPLICABLE", null, outcome.reason.value, emptyMap())
    StepOutcome.NotReached -> PaymentHeldEvent.HeldStep(record.step.name, "NOT_REACHED", null, null, emptyMap())
}
