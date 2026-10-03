// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.application.port.out

import java.time.Instant

/**
 * The ADR-0214 D3 evidence bundle for one loan application, read from the tamper-evident audit
 * chain (ADR-0133) — never from this service's own outbox, which is a delivery buffer and is purged
 * once delivered (ADR-0329, #11900).
 */
interface LoanEvidencePort {
    /** Throws [LoanEvidenceUnavailable] when the chain cannot be read; it never falls back. */
    suspend fun bundleFor(applicationId: String): LoanEvidence
}

data class LoanEvidence(
    val attestation: String,
    val truncated: Boolean,
    val tampered: Boolean,
    val events: List<LoanEvidenceEvent>,
)

data class LoanEvidenceEvent(
    val eventId: String,
    val eventType: String,
    val sourceService: String,
    val occurredAt: Instant,
    val payload: String,
    val hashStatus: String,
)

/**
 * The chain could not be read. [status] is the upstream HTTP status when there was one (401/403 are
 * the caller's own identity being refused by audit-service, and are passed through as such), or null
 * when audit-service was unreachable.
 */
class LoanEvidenceUnavailable(val status: Int?, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
