// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

/**
 * Raised when `openbank-sanctions-service` cannot be reached to run the PEP screen. Unlike the
 * payment-path sanctions gate (ADR-0032 §C, which fails closed and holds the payment), a KYC case
 * does not have a safe "hold" state to fall back on mid check-evaluation — [PepScreeningResult]
 * models this as [PepScreeningStatus.UNAVAILABLE] instead of throwing across the port boundary, so
 * the caller can route it to [com.openbank.kyc.domain.model.CheckStatus.MANUAL_REVIEW] rather than
 * silently recording a false PASSED.
 */
class PepScreeningUnavailableException(cause: Throwable) :
    RuntimeException("PEP screening (openbank-sanctions-service) is unavailable", cause)

enum class PepScreeningStatus { CLEAR, POTENTIAL_MATCH, MATCH, UNAVAILABLE }

/**
 * Outcome of screening a party name against the PEP_GLOBAL list type held by
 * `openbank-sanctions-service` (OpenSanctions free PEP dataset — see ADR-0116 delivery note).
 * This is a first, free-data-source PEP check — not a paid commercial vendor feed
 * (Refinitiv/ComplyAdvantage/etc.), not identity-document verification, and not continuous
 * real-time monitoring.
 */
data class PepScreeningResult(val status: PepScreeningStatus, val matchScore: Double, val matchedName: String?)

/**
 * Outbound port to `openbank-sanctions-service`'s synchronous screen endpoint
 * (`POST /api/v1/sanctions/screen`), scoped to the `PEP_GLOBAL` list type only — this is a
 * dedicated PEP check, distinct from the broader `SANCTIONS_SCREENING` KYC check (which remains
 * manual/operator-driven pending its own integration, out of scope here).
 */
interface PepScreeningPort {
    suspend fun screenForPep(name: String, idempotencyKey: String): PepScreeningResult
}
