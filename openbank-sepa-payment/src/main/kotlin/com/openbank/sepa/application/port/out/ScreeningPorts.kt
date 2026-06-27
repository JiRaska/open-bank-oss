// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.port.out

import com.openbank.sepa.domain.screening.ScreeningResult
import com.openbank.sepa.domain.screening.ScreeningRole
import java.util.UUID

/** Raised when the sanctions service cannot be reached — the gate fails closed (ADR-0032 §C). */
class ScreeningUnavailableException(cause: Throwable) :
    RuntimeException("Sanctions screening is unavailable; payment held", cause)

/**
 * Outbound port to the sanctions service's synchronous screen
 * (`POST /api/v1/sanctions/screen`). The adapter maps the remote `SanctionsCheck` onto the local
 * [ScreeningResult] and throws [ScreeningUnavailableException] if the service is unreachable so the
 * use-case can hold the payment rather than release it un-screened.
 */
interface SanctionsScreeningPort {
    suspend fun screen(name: String, role: ScreeningRole, idempotencyKey: String): ScreeningResult
}

/** Risk grading of an opened AML case, mirrors aml-service `AmlRiskLevel`. */
enum class AmlCaseRiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

/** Everything the use-case knows at the payment boundary to open an AML case. */
data class OpenAmlCaseCommand(
    val idempotencyKey: String,
    val paymentId: UUID,
    val debtorAccountId: UUID,
    val customerReference: String,
    val riskLevel: AmlCaseRiskLevel,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
)

/**
 * Outbound port to the aml-service case store (`POST /api/v1/aml/cases`, idempotent on the
 * Idempotency-Key header). Opening a case is best-effort follow-up signalling — it must never change
 * the screening verdict already rendered.
 */
interface AmlCasePort {
    suspend fun openCase(command: OpenAmlCaseCommand)
}
