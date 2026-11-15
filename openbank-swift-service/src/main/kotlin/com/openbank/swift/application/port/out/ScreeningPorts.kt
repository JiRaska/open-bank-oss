// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.port.out

import com.openbank.libs.fincrime.AmlCaseSeverity
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import java.util.UUID

/** Raised when the sanctions service cannot be reached — the gate fails closed (ADR-0032 §C). */
class ScreeningUnavailableException(cause: Throwable) :
    RuntimeException("Sanctions screening is unavailable; SWIFT message held", cause)

/**
 * Outbound port to the sanctions service's synchronous screen (`POST /api/v1/sanctions/screen`).
 * Throws [ScreeningUnavailableException] when the service is unreachable, so the caller holds the
 * message instead of releasing it un-screened.
 */
interface SanctionsScreeningPort {
    suspend fun screen(name: String, role: ScreeningRole, idempotencyKey: String): ScreeningResult
}

/** Everything the rail knows at the message boundary to open an AML case. */
data class OpenAmlCaseCommand(
    val idempotencyKey: String,
    val messageId: UUID,
    val orderingCustomerAccountId: UUID,
    val customerReference: String,
    val riskLevel: AmlCaseSeverity,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
)

/**
 * Outbound port to the aml-service case store (`POST /api/v1/aml/cases`, idempotent on the
 * Idempotency-Key header). Best-effort follow-up: it never changes a verdict already rendered.
 */
interface AmlCasePort {
    suspend fun openCase(command: OpenAmlCaseCommand)
}
