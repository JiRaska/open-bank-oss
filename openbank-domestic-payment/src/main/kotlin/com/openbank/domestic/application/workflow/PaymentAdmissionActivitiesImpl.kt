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
import com.openbank.domestic.domain.model.HoldDecision
import com.openbank.domestic.domain.model.RecordedFraudScore
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
import org.jboss.logging.Logger
import java.security.MessageDigest
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
 * payment's row, so a Temporal retry of an attempt that already committed changes nothing and
 * answers what that attempt decided: a status that is past admission answers for itself, and a HELD
 * payment whose case was requested answers "held for review" without asking any control again.
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
) : PaymentAdmissionActivities {

    private val log = Logger.getLogger(PaymentAdmissionActivitiesImpl::class.java)

    protected open fun <T> vtx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    override fun admit(paymentId: UUID, lastAttempt: Boolean): AdmissionAction = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found during admission activity")
        when (payment.status) {
            // Whether a payment goes through admission was decided when its run was started, by the
            // task queue it was started on. The activity does not ask again.
            DomesticPaymentStatus.RECEIVED -> evaluate(payment, lastAttempt)
            DomesticPaymentStatus.HELD ->
                // Its case was requested: the hold is the case's to decide. Evaluating again could
                // let a control that answers differently now release a payment a reviewer holds.
                if (payment.holdCaseRequestedAt != null) {
                    AdmissionAction.HELD_FOR_REVIEW
                } else {
                    evaluate(payment, lastAttempt)
                }
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.SENT_TO_CLEARING,
            DomesticPaymentStatus.SETTLED,
            DomesticPaymentStatus.RETURNED,
            -> AdmissionAction.ADMITTED
            DomesticPaymentStatus.REJECTED, DomesticPaymentStatus.CANCELLED -> AdmissionAction.REJECTED
        }
    }

    private suspend fun evaluate(payment: DomesticPayment, lastAttempt: Boolean): AdmissionAction {
        var freshFraudScore: RecordedFraudScore? = null
        val result = chain.admit(payment) { freshFraudScore = it }
        // Kept before anything else is written, so an attempt that fails further down still does
        // not ask fraud-service about this payment a second time.
        freshFraudScore?.let { writes.rememberFraudScore(payment.id, it) }
        // One count per decision that changes the payment. A payment that is HELD and still held
        // after a re-evaluation was counted when it was first held.
        val stillHeld = payment.status == DomesticPaymentStatus.HELD &&
            (result.decision == AdmissionDecision.HOLD || result.decision == AdmissionDecision.CHALLENGE)
        if (!stillHeld) metrics.recordDecision(result.decision)
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
            AdmissionDecision.REJECT -> {
                if (payment.status == DomesticPaymentStatus.HELD) {
                    metrics.recordHoldEnd(PaymentHoldEnd.REJECTED_BY_REEVALUATION)
                }
                rejected(payment, result)
            }
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
        val reasons = result.coveredByCase()
        if (payment.status == DomesticPaymentStatus.RECEIVED) {
            val held = payment.transitionTo(DomesticPaymentStatus.HELD, clock = clock)
            val updated = if (wantsReview) held.withHoldCaseRequested(Instant.now(clock), reasons) else held
            val events = buildList {
                add(eventPublisher.statusChanged(payment, updated))
                if (wantsReview) add(heldEvent(updated, result))
            }
            writes.transition(updated, events)
        } else if (wantsReview &&
            !writes.requestCaseWhileHeld(payment.id, heldEvent(payment, result), Instant.now(clock), reasons)
        ) {
            // It left HELD between the read and the write; the workflow's next step reads the truth.
            log.warnf("Payment %s left HELD while its review was being requested", payment.id)
        }
        return if (wantsReview) AdmissionAction.HELD_FOR_REVIEW else AdmissionAction.HELD_UNAVAILABLE
    }

    override fun concludeHold(paymentId: UUID): HoldState = vtx {
        val payment = paymentRepository.findById(paymentId)
            ?: error("Payment $paymentId not found while concluding its hold")
        when (payment.status) {
            DomesticPaymentStatus.HELD -> {
                val expiresAt = checkNotNull(payment.holdExpiresAt(PaymentHoldPolicy.EXPIRY))
                when {
                    // Expiry first, and from the row: a hold that has run its term is rejected
                    // whatever its case decided, whenever that decision arrived and whichever run
                    // gets here (ADR-0374 D3).
                    !Instant.now(clock).isBefore(expiresAt) -> rejectHeld(payment, HoldRejectCause.EXPIRED)
                    payment.holdDecision == null -> HoldState.WAITING
                    payment.holdDecision == HoldDecision.REJECT ->
                        rejectHeld(payment, HoldRejectCause.REVIEW_REJECTED)
                    else -> release(payment)
                }
            }
            DomesticPaymentStatus.REJECTED, DomesticPaymentStatus.CANCELLED -> HoldState.REJECTED
            DomesticPaymentStatus.RECEIVED -> error("Payment $paymentId is not held; it has no hold to conclude")
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.SENT_TO_CLEARING,
            DomesticPaymentStatus.SETTLED,
            DomesticPaymentStatus.RETURNED,
            -> HoldState.RELEASED
        }
    }

    /**
     * The one place a HELD payment becomes VALIDATED on a case decision, and so the one place that
     * holds the rule: a payment is sent only if every control bound on this rail answered for it,
     * none refuses, and every "review" among the answers is one the released case was shown.
     *
     * A case decision covers exactly the evidence the case was shown ([DomesticPayment.holdCaseReasons],
     * recorded with the case request: step, reason and a fingerprint of that step's evidence) and
     * nothing else. The same reason on other evidence, another matched list entry or another score,
     * is a different question. It does not answer for a control that never
     * spoke, and it does not answer a question nobody put to the reviewer. So the whole chain is
     * evaluated again here, hours after the hold began:
     *
     *  - a control refuses: REJECTED;
     *  - a control still cannot answer: the payment stays HELD and the run looks again later;
     *  - a control asks for a review the case did not cover (a control that was down when the case
     *    was opened and now reports a match; a list that changed during the hold): the payment
     *    stays HELD and a new review is requested for what the controls say now, as a case of its
     *    own, inside the same hold and the same expiry;
     *  - every control allows, or asks for a review this case already decided: VALIDATED.
     */
    private suspend fun release(payment: DomesticPayment): HoldState {
        var freshFraudScore: RecordedFraudScore? = null
        val result = chain.admit(payment) { freshFraudScore = it }
        freshFraudScore?.let { writes.rememberFraudScore(payment.id, it) }
        val unanswered = result.steps.filter { it.outcome is StepOutcome.Unavailable }
        // A review whose evidence has no identity is never covered, whatever the row says.
        val uncovered = result.reviewed().filter { it.identity == null || it.covered() !in payment.holdCaseReasons }
            .map { it.reason }
        return when {
            result.decision == AdmissionDecision.REJECT -> {
                rejected(payment, result)
                metrics.recordHoldEnd(PaymentHoldEnd.RELEASE_REFUSED)
                HoldState.REJECTED
            }
            unanswered.isNotEmpty() -> {
                unanswered.forEach { record ->
                    metrics.recordUnavailable(record.step, (record.outcome as StepOutcome.Unavailable).cause)
                }
                log.warnf(
                    "Held payment %s was released by its case but %s could not answer; it stays HELD",
                    payment.id,
                    unanswered.map { it.step },
                )
                HoldState.WAITING
            }
            uncovered.isNotEmpty() -> {
                val next = payment.copy(holdReview = payment.holdReview + 1, holdCaseReasons = result.coveredByCase())
                val asked = writes.requestNextReview(
                    payment.id,
                    fromReview = payment.holdReview,
                    reasons = next.holdCaseReasons,
                    event = heldEvent(next, result),
                )
                log.warnf(
                    "Held payment %s was released by its case, but %s was not what that case decided; " +
                        "it stays HELD and review %d is requested (written by this attempt: %s)",
                    payment.id,
                    uncovered,
                    next.holdReview,
                    asked,
                )
                HoldState.WAITING
            }
            else -> {
                val updated = payment.transitionTo(DomesticPaymentStatus.VALIDATED, clock = clock)
                writes.transition(updated, listOf(eventPublisher.statusChanged(payment, updated)))
                metrics.recordHoldEnd(PaymentHoldEnd.RELEASED)
                log.infof("Held payment %s released by its case", payment.id)
                HoldState.RELEASED
            }
        }
    }

    private suspend fun rejectHeld(payment: DomesticPayment, cause: HoldRejectCause): HoldState {
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
        log.infof("Held payment %s REJECTED (cause=%s)", payment.id, cause)
        return HoldState.REJECTED
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

/** One control that answered "a human must look", with the identity of the evidence it showed. */
private class Reviewed(val step: AdmissionStep, val reason: String, val identity: String?) {
    /** What a decision on this review covers, as recorded on the row. Only meaningful with an identity. */
    fun covered(): String = "${step.name}:$reason:$identity"
}

private fun AdmissionResult.reviewed(): List<Reviewed> = steps.mapNotNull { record ->
    val outcome = record.outcome as? StepOutcome.Decided
    val wantsHuman = outcome?.decision == AdmissionDecision.HOLD || outcome?.decision == AdmissionDecision.CHALLENGE
    if (outcome != null && wantsHuman) {
        Reviewed(record.step, outcome.reason.value, evidenceIdentity(record.step, outcome.evidence.attributes))
    } else {
        null
    }
}

/**
 * What the case of this result is asked to decide, and so what its decision will cover: step,
 * reason and evidence identity of every control that wants a human. A control whose evidence has no
 * identity contributes nothing, so no decision ever covers it. A control that could not answer is
 * not in it either; nobody can decide for a control that did not speak.
 */
private fun AdmissionResult.coveredByCase(): Set<String> =
    reviewed().filter { it.identity != null }.map { it.covered() }.toSet()

/**
 * A fingerprint of the evidence a control showed, from what the control itself returned, or null
 * when that evidence does not say WHAT was found. Canonical: the attributes sorted by key, so the
 * order a control lists things in neither fakes a change nor hides one.
 *
 *  - Sanctions: status, score and matched list entry of each screened role. A role that is not
 *    clear and names no matched entry has no identity: "something matched" cannot be told from
 *    "something else matched". The screening contract returns no entry id and no list version, so
 *    an entry changed in place under the same name and score is not told apart either.
 *  - Fraud: verdict, score and rule version.
 *  - Any other step has no identity until someone says here what its evidence is.
 */
private fun evidenceIdentity(step: AdmissionStep, evidence: Map<String, String>): String? {
    val identified = when (step) {
        AdmissionStep.SANCTIONS -> evidence.keys.filter { it.endsWith("_status") }.let { statuses ->
            statuses.isNotEmpty() &&
                statuses.all { key ->
                    evidence[key] in SANCTIONS_CLEAR_STATUSES ||
                        !evidence[key.removeSuffix("_status") + "_matched_entity"].isNullOrBlank()
                }
        }
        AdmissionStep.FRAUD -> FRAUD_IDENTITY.all { !evidence[it].isNullOrBlank() }
        else -> false
    }
    if (!identified) return null
    val canonical = evidence.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        .take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
}

private val SANCTIONS_CLEAR_STATUSES = setOf("CLEAR", "WHITELISTED")
private val FRAUD_IDENTITY = listOf("verdict", "score", "rule_version")
private const val FINGERPRINT_BYTES = 8

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
        caseKey = PaymentHoldPolicy.caseKey(held.id, held.holdReview),
        review = held.holdReview,
        debtorAccountId = held.debtorAccountId,
        debtorPartyId = debtorPartyId,
        customerReference = "${held.debtorAccountNumber}/${held.debtorBankCode}",
        reasons = result.reasons.map { it.value },
        steps = result.steps.map(::heldStep),
        // Rows held by a build from before held_at existed fall back to the last write of the row.
        heldAt = held.heldAt ?: held.updatedAt,
        expiresAt = (held.heldAt ?: held.updatedAt).plus(PaymentHoldPolicy.EXPIRY),
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
