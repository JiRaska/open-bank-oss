// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

import com.openbank.kyc.domain.model.CheckStatus

/**
 * Outcome of an adverse-media screen. Deliberately **four-valued**: "we could not screen" is never
 * the same value as "we screened and found nothing".
 *
 * This repo has paid for the opposite shape twice — a disabled push adapter whose
 * `skipped()` result carried `success = true` counted every undelivered push as delivered
 * (ADR-0252 phase 0, #4348), and a dead-letter sink whose `quarantine()` was a single log line
 * made "quarantined" mean "logged". An adverse-media check that cannot reach a source must not be
 * representable as a clean result, so [NO_HIT] is a distinct enum member that only a reachable,
 * configured source can produce.
 */
enum class AdverseMediaOutcome {
    /** The source was reached and returned at least one candidate article/record for the subject. */
    HIT,

    /**
     * The source was reached and returned nothing. **Only** a configured, reachable source may
     * yield this — it is the sole outcome that maps to [CheckStatus.PASSED].
     */
    NO_HIT,

    /** A source is configured but could not be reached (transport fault, timeout, open circuit). */
    SOURCE_UNAVAILABLE,

    /**
     * No adverse-media source is configured on this platform at all. This is the state OpenBank is
     * in today: ADR-0256 D5 defers adverse media because no change-detectable, EU-residency-compatible,
     * licensed source has been selected (issue #4459). It is a *permanent* unresolved state, not a
     * transient one, and is reported as such rather than as an absence of findings.
     */
    SOURCE_NOT_CONFIGURED,
    ;

    /**
     * Fail-closed mapping onto the KYC check lifecycle. Exhaustive by construction: a new outcome
     * member will not compile until it declares which side of the line it falls on, and every side
     * except [NO_HIT] is [CheckStatus.MANUAL_REVIEW] — an unresolved check a human must dispose of,
     * never a [CheckStatus.PASSED] the platform did not earn.
     *
     * Note the deliberate asymmetry with a hit: [HIT] is also MANUAL_REVIEW, not FAILED, because
     * ADR-0116's four-eyes gate owns the disposition — a trigger may never decide anything about a
     * customer (ADR-0256 D2).
     */
    fun toCheckStatus(): CheckStatus = when (this) {
        NO_HIT -> CheckStatus.PASSED
        HIT, SOURCE_UNAVAILABLE, SOURCE_NOT_CONFIGURED -> CheckStatus.MANUAL_REVIEW
    }

    /** True when this outcome represents an actual observation of the source's contents. */
    val isResolved: Boolean get() = this == HIT || this == NO_HIT
}

/**
 * Result of one adverse-media screen. [sourceId] is null exactly when [outcome] is
 * [AdverseMediaOutcome.SOURCE_NOT_CONFIGURED] — the provenance of a screening result is part of
 * the result, so a case's check can record *which* source (and therefore which coverage) backed it.
 */
data class AdverseMediaScreeningResult(
    val outcome: AdverseMediaOutcome,
    val sourceId: String?,
    val matchedHeadline: String? = null,
) {
    init {
        require((sourceId == null) == (outcome == AdverseMediaOutcome.SOURCE_NOT_CONFIGURED)) {
            "sourceId must be null iff outcome is SOURCE_NOT_CONFIGURED (was outcome=$outcome, sourceId=$sourceId)"
        }
    }
}

/**
 * Outbound port for adverse-media screening of a party name.
 *
 * **There is no production implementation backed by a real source today.** ADR-0256 D5 keeps
 * adverse media out of the perpetual-KYC trigger catalogue precisely because a stub would make
 * `CheckType.ADVERSE_MEDIA` decorative; this port exists so that when a source is selected
 * (#4459) it lands behind a contract that cannot express "clean" without having read something,
 * and so that the platform's *lack* of coverage is observable today rather than implicit.
 */
interface AdverseMediaScreeningPort {

    /**
     * Stable identifier of the backing source, or **null when no source is configured**. The
     * readiness gauge is derived from this rather than from a separate config key, so the reported
     * coverage cannot drift from the adapter actually wired in.
     */
    val sourceId: String?

    suspend fun screen(name: String, idempotencyKey: String): AdverseMediaScreeningResult
}
