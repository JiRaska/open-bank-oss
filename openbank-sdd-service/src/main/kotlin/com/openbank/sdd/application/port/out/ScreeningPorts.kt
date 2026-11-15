// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.application.port.out

import com.openbank.libs.fincrime.AmlCaseSeverity
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import io.smallrye.mutiny.Uni
import java.util.UUID

/** Raised when the sanctions service cannot be reached: the collection gate fails closed (ADR-0032 §C). */
class ScreeningUnavailableException(cause: Throwable) :
    RuntimeException("Sanctions screening is unavailable; collection held", cause)

/**
 * Outbound port to sanctions-service's synchronous screen (`POST /api/v1/sanctions/screen`).
 * A failure to obtain an answer surfaces as [ScreeningUnavailableException], never as a CLEAR.
 */
interface SanctionsScreeningPort {
    fun screen(name: String, role: ScreeningRole, idempotencyKey: String): Uni<ScreeningResult>
}

/** Everything the collection gate knows when it opens an AML case. */
data class OpenAmlCaseCommand(
    val idempotencyKey: String,
    val mandateId: UUID,
    val debtorAccountId: UUID,
    val customerReference: String,
    val riskLevel: AmlCaseSeverity,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
)

/**
 * Outbound port to aml-service's case store (`POST /api/v1/aml/cases`, idempotent on the
 * Idempotency-Key header). Best-effort signalling: a failure here never changes the verdict.
 */
interface AmlCasePort {
    fun openCase(command: OpenAmlCaseCommand): Uni<Void>
}
