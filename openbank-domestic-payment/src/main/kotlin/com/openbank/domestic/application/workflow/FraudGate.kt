// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.application.port.out.FraudScoreOutcome
import com.openbank.libs.fincrime.FraudVerdict

/** What the workflow does with a scored payment (ADR-0084 §4.2, #4403). */
enum class FraudGateAction { PROCEED, HOLD, REJECT }

/** How a fraud decline or hold ended in rejection; carried by the reject activity and its metric. */
enum class FraudRejectCause { DECLINED, REVIEW_REJECTED, HOLD_TIMED_OUT }

/** An analyst's answer to a held payment, delivered by [DomesticPaymentWorkflow.resolveFraudHold]. */
enum class FraudReviewDecision { RELEASE, REJECT }

/**
 * The enforcement table, kept pure so every row is unit-testable and the workflow only branches on
 * its result.
 *
 * With enforcement off nothing is stopped: the would-be action is still computed (so shadow
 * calibration can read it from the metric) but the effective action is always
 * [FraudGateAction.PROCEED].
 *
 * With enforcement on it fails CLOSED: only a REAL `ALLOW` proceeds. A synthetic verdict, the
 * adapter's stand-in when fraud-service could not be reached, is held whatever it says, because a
 * payment that was never scored is not a payment that scored clean (#4221).
 */
object FraudGatePolicy {
    fun wouldDo(outcome: FraudScoreOutcome): FraudGateAction = when {
        outcome.synthetic -> FraudGateAction.HOLD
        outcome.verdict == FraudVerdict.ALLOW -> FraudGateAction.PROCEED
        outcome.verdict == FraudVerdict.DECLINE -> FraudGateAction.REJECT
        // CHALLENGE, REVIEW, UNKNOWN and any verdict added later.
        else -> FraudGateAction.HOLD
    }

    fun effective(outcome: FraudScoreOutcome, enforcementEnabled: Boolean): FraudGateAction =
        if (enforcementEnabled) wouldDo(outcome) else FraudGateAction.PROCEED
}
