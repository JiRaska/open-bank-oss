// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.screening.ScreeningDecision
import io.temporal.activity.ActivityInterface
import java.util.UUID

@ActivityInterface
interface DomesticPaymentActivities {
    fun screenPayment(paymentId: UUID): ScreeningDecision
    fun validatePayment(paymentId: UUID)
    fun rejectPayment(paymentId: UUID)
    fun shadowFraudScore(paymentId: UUID)

    /** ADR-0104 D4: submit the validated payment to the scheme and advance to SENT_TO_CLEARING or REJECTED. */
    fun submitScheme(paymentId: UUID): DomesticPaymentStatus

    /**
     * ADR-0108: book the funds in transaction-service after the scheme confirms ACSC.
     * Returns SETTLED on success; stays SENT_TO_CLEARING if the service is unavailable
     * (Temporal retries via the activity retry policy).
     */
    fun settlePayment(paymentId: UUID): DomesticPaymentStatus
}
