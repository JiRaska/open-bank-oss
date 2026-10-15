// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

import com.openbank.kyc.domain.model.ScreeningTriggerFailure
import java.util.UUID

/**
 * Outbound port for the case-coordinator case-open call (ADR-0256 contract:
 * `POST /api/v1/case-coordinator/cases`, caseClass AML_ALERT, openedBy kyc-service,
 * dispositionTarget kyc-rescreen). Implemented by
 * [com.openbank.kyc.infrastructure.client.CaseCoordinatorAdapter].
 *
 * The port speaks outcomes, not HTTP: the coordinator's 201/409 are both success (409 is an
 * idempotent duplicate — the case already runs), while 429/503/timeouts are
 * [CaseOpenOutcome.Retryable] and unrecoverable 4xx are [CaseOpenOutcome.Rejected]. Callers
 * persist the failure durably rather than swallow it.
 */
interface CaseCoordinatorPort {

    suspend fun openAmlAlertCase(partyId: UUID): CaseOpenOutcome
}

sealed interface CaseOpenOutcome {
    /** 201 Created — carries the coordinator case id. */
    data class Opened(val caseId: String) : CaseOpenOutcome

    /** 409 Conflict — a case for this class and subject already runs; nothing to do. */
    data object AlreadyExists : CaseOpenOutcome

    /** 429/503/timeout — the coordinator may recover; worth another attempt later. */
    data class Retryable(val detail: String) : CaseOpenOutcome

    /** A 4xx retrying cannot fix; [reason] classifies the durable failure record. */
    data class Rejected(val reason: ScreeningTriggerFailure, val detail: String) : CaseOpenOutcome
}
