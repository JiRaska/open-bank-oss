// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.application.workflow

import com.openbank.sepa.domain.model.SepaPaymentStatus
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

@WorkflowInterface
interface SepaPaymentWorkflow {
    @WorkflowMethod
    fun process(paymentId: UUID): SepaPaymentStatus
}
