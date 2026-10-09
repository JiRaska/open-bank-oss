// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.onboarding.workflow

import com.openbank.pension.application.onboarding.DispatchResult
import com.openbank.pension.application.onboarding.OnboardingTimers
import com.openbank.pension.application.onboarding.TransferInTimers
import com.openbank.pension.domain.onboarding.ActivationTrigger
import com.openbank.pension.domain.transfer.FundsArrival
import com.openbank.pension.domain.transfer.TransferStatus
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
import java.util.UUID

/**
 * Workflow ids are derived from the aggregate id, so starting twice is a no-op and a signal always
 * finds its workflow (ADR-0334 §4: every lifecycle step is a Temporal workflow).
 */
object PensionWorkflowIds {
    fun onboarding(applicationId: UUID) = "pension-onboarding-$applicationId"
    fun transferIn(transferId: UUID) = "pension-transfer-in-$transferId"
    fun transferOut(transferId: UUID) = "pension-transfer-out-$transferId"
}

enum class OnboardingOutcome { ACTIVATED, WITHDRAWN, EXPIRED }

enum class TransferOutcome { COMPLETED, REJECTED, TIMED_OUT, CANCELLED, FAILED }

// --- activities --------------------------------------------------------------------------------

@ActivityInterface
interface OnboardingActivities {
    /** PENDING_ACTIVATION -> ACTIVE; false when the application already left SIGNED (withdrawn). */
    fun activate(applicationId: UUID): Boolean
    fun expire(applicationId: UUID, reason: String)
}

@ActivityInterface
interface TransferInActivities {
    fun dispatch(transferId: UUID): DispatchResult
    fun recordAccepted(transferId: UUID)
    fun complete(transferId: UUID, arrival: FundsArrival)
    fun fail(transferId: UUID, outcome: TransferStatus, reason: String)
}

@ActivityInterface
interface TransferOutActivities {
    fun valuate(transferId: UUID): Boolean
    fun redeem(transferId: UUID): String
    fun settle(transferId: UUID)
    fun compensate(transferId: UUID, reason: String)
}

internal object PensionActivityOptions {
    private const val MAX_ATTEMPTS = 20
    private const val BACKOFF = 2.0

    /**
     * A domain refusal (`IllegalStateException` / `IllegalArgumentException`) is final — retrying it
     * only delays the compensation. Anything else (a collaborator down) is retried with backoff.
     */
    val DEFAULT: ActivityOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(2))
                .setBackoffCoefficient(BACKOFF)
                .setMaximumInterval(Duration.ofMinutes(10))
                .setMaximumAttempts(MAX_ATTEMPTS)
                .setDoNotRetry(IllegalStateException::class.java.name, IllegalArgumentException::class.java.name)
                .build(),
        )
        .build()
}

private fun days(n: Int): Long = Duration.ofDays(n.toLong()).toMillis()

// --- new contract --------------------------------------------------------------------------------

@WorkflowInterface
interface OnboardingWorkflow {
    @WorkflowMethod
    fun run(applicationId: UUID, timers: OnboardingTimers): OnboardingOutcome

    @SignalMethod
    fun contributionReceived()

    /** The REST withdrawal already closed the contract; the workflow only stops waiting. */
    @SignalMethod
    fun withdrawn()
}

/**
 * Signed → (activation trigger) → ACTIVE, with the cooling-off window running alongside. A
 * contract never funded before the pack's activation deadline lapses and is closed.
 */
class OnboardingWorkflowImpl : OnboardingWorkflow {

    private val activities = Workflow.newActivityStub(OnboardingActivities::class.java, PensionActivityOptions.DEFAULT)
    private var contribution = false
    private var withdrawal = false

    override fun contributionReceived() {
        contribution = true
    }

    override fun withdrawn() {
        withdrawal = true
    }

    override fun run(applicationId: UUID, timers: OnboardingTimers): OnboardingOutcome {
        val start = Workflow.currentTimeMillis()
        val coolingOffEnd = start + days(timers.coolingOffDays)
        val activationDeadline = start + days(timers.activationDeadlineDays)
        var activated = false
        if (timers.activationTrigger == ActivationTrigger.SIGNATURE) {
            if (!activities.activate(applicationId)) return OnboardingOutcome.WITHDRAWN
            activated = true
        }
        while (true) {
            if (withdrawal) return OnboardingOutcome.WITHDRAWN
            if (!activated && contribution) {
                if (!activities.activate(applicationId)) return OnboardingOutcome.WITHDRAWN
                activated = true
            }
            val now = Workflow.currentTimeMillis()
            if (activated && now >= coolingOffEnd) return OnboardingOutcome.ACTIVATED
            if (!activated && now >= activationDeadline) {
                activities.expire(applicationId, "contract not funded within ${timers.activationDeadlineDays} days")
                return OnboardingOutcome.EXPIRED
            }
            val wakeAt = if (activated) coolingOffEnd else activationDeadline
            val isActivated = activated
            Workflow.await(Duration.ofMillis(wakeAt - now)) { withdrawal || (!isActivated && contribution) }
        }
    }
}

// --- transfer-in ---------------------------------------------------------------------------------

@WorkflowInterface
interface TransferInWorkflow {
    @WorkflowMethod
    fun run(transferId: UUID, timers: TransferInTimers): TransferOutcome

    @SignalMethod
    fun accepted()

    @SignalMethod
    fun rejected(reason: String)

    @SignalMethod
    fun fundsReceived(arrival: FundsArrival)

    @SignalMethod
    fun withdrawn()
}

/**
 * Send → (accepted) → funds arrive → contract ACTIVE with its original start date. Every way it
 * can fail — refusal, rejection, timeout, withdrawal, an unreachable ceding provider — ends in
 * `fail`, which compensates (withdraws the outstanding request, closes the unfunded contract).
 */
class TransferInWorkflowImpl : TransferInWorkflow {

    private val activities = Workflow.newActivityStub(TransferInActivities::class.java, PensionActivityOptions.DEFAULT)
    private var acceptance = false
    private var rejection: String? = null
    private var arrival: FundsArrival? = null
    private var withdrawal = false

    override fun accepted() {
        acceptance = true
    }

    override fun rejected(reason: String) {
        rejection = reason
    }

    override fun fundsReceived(arrival: FundsArrival) {
        this.arrival = arrival
    }

    override fun withdrawn() {
        withdrawal = true
    }

    override fun run(transferId: UUID, timers: TransferInTimers): TransferOutcome {
        val dispatched = try {
            activities.dispatch(transferId)
        } catch (e: ActivityFailure) {
            activities.fail(transferId, TransferStatus.FAILED, "ceding provider unreachable: ${e.cause?.message}")
            return TransferOutcome.FAILED
        }
        if (dispatched == DispatchResult.ENDED) return TransferOutcome.FAILED
        var limit = Workflow.currentTimeMillis() + days(timers.responseDeadlineDays)
        var acceptanceRecorded = false
        while (true) {
            val outcome = settle(transferId)
            if (outcome != null) return outcome
            if (acceptance && !acceptanceRecorded) {
                activities.recordAccepted(transferId)
                acceptanceRecorded = true
                limit = Workflow.currentTimeMillis() + days(timers.fundsGraceDays)
            }
            val now = Workflow.currentTimeMillis()
            if (now >= limit) {
                activities.fail(transferId, TransferStatus.TIMED_OUT, "the ceding provider did not complete in time")
                return TransferOutcome.TIMED_OUT
            }
            val recorded = acceptanceRecorded
            Workflow.await(Duration.ofMillis(limit - now)) {
                withdrawal || rejection != null || arrival != null || (acceptance && !recorded)
            }
        }
    }

    private fun settle(transferId: UUID): TransferOutcome? {
        val reason = rejection
        val funds = arrival
        return when {
            withdrawal -> {
                activities.fail(transferId, TransferStatus.CANCELLED, "withdrawn by the participant")
                TransferOutcome.CANCELLED
            }
            reason != null -> {
                activities.fail(transferId, TransferStatus.REJECTED, reason)
                TransferOutcome.REJECTED
            }
            funds != null -> {
                activities.complete(transferId, funds)
                TransferOutcome.COMPLETED
            }
            else -> null
        }
    }
}

// --- transfer-out --------------------------------------------------------------------------------

@WorkflowInterface
interface TransferOutWorkflow {
    @WorkflowMethod
    fun run(transferId: UUID): TransferOutcome
}

/**
 * Value → redeem units → pay the receiving provider → contract TRANSFERRED_OUT. A failure before
 * the payment reverses the redemption (compensation); the payment itself is keyed by the transfer
 * id at the counterparty, so a retried settle cannot pay twice.
 */
class TransferOutWorkflowImpl : TransferOutWorkflow {

    private val activities = Workflow.newActivityStub(TransferOutActivities::class.java, PensionActivityOptions.DEFAULT)

    override fun run(transferId: UUID): TransferOutcome {
        return try {
            if (!activities.valuate(transferId)) return TransferOutcome.REJECTED
            activities.redeem(transferId)
            activities.settle(transferId)
            TransferOutcome.COMPLETED
        } catch (e: ActivityFailure) {
            activities.compensate(transferId, "transfer-out failed: ${e.cause?.message}")
            TransferOutcome.FAILED
        }
    }
}
