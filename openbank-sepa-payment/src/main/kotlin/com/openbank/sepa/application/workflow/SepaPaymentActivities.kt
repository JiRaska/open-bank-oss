// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.application.workflow

import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.screening.ScreeningDecision
import io.temporal.activity.ActivityInterface
import java.util.UUID

@ActivityInterface
interface SepaPaymentActivities {
    fun screenPayment(paymentId: UUID): ScreeningDecision
    fun validatePayment(paymentId: UUID)
    fun rejectPayment(paymentId: UUID)
    fun shadowFraudScore(paymentId: UUID)

    /**
     * ADR-0104 D3: submit a VALIDATED payment's real ISO 20022 `pacs.008` to the scheme gateway and
     * advance it on the `pacs.002` verdict — `ACSC` → PROCESSING, `RJCT` → REJECTED (mapped reason).
     * Fails closed: an unreachable gateway leaves the payment VALIDATED (held, never silently released).
     * No-op (returns the current status) unless the pilot flag is on and the payment is VALIDATED.
     * Returns the resulting [SepaPaymentStatus] so the workflow can report the terminal-ish state.
     */
    fun submitToScheme(paymentId: UUID): SepaPaymentStatus
}
