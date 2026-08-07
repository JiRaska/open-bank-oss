// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The adverse-state set ADR-0220 D1/D3.5 names verbatim: fraud-hold, arrears, dispute-opened,
 * erasure. The platform already emits domain events for all four elsewhere in the fleet; this
 * enum names the set this ADR reads, not a new source of truth.
 */
enum class AdverseState { FRAUD_HOLD, ARREARS, DISPUTE_OPENED, ERASURE_REQUESTED }

/**
 * A pre-computed eligibility snapshot for one party (ADR-0220 D1). In the shipped design this is
 * materialised event-driven into the engagement service's own store; this domain layer only
 * defines the shape and the rule over it, not the materialisation pipeline (infrastructure,
 * follow-up PR).
 */
data class EligibilitySnapshot(val partyId: UUID, val adverseState: Set<AdverseState>, val asOf: Instant)

/**
 * ADR-0220 D3.5 — vulnerable customers are excluded from *targeting*, never from the surface
 * itself. This function answers only "may the platform proactively show this person a promotional
 * surface" — it says nothing about a customer who opens `rewards_hub` on their own initiative,
 * which stays available regardless of adverse state.
 *
 * This is deliberately a SEPARATE gate from consent and frequency (`ContactPolicyGate`, already
 * shipped in `openbank-libs-runtime` and reused by campaign-service). Consent answers "may we
 * contact this person at all"; this answers "is this specific person safe to target with
 * marketing right now". Collapsing the two would let a fraud-hold party still receive a
 * promotional surface as long as they happen to have marketing consent, which is exactly the
 * compliance defect D1 calls out — "a vulnerable-customer exclusion that lags... is a compliance
 * defect, not a freshness metric."
 */
object EligibilityRule {
    fun isEligibleForPromotionalTargeting(snapshot: EligibilitySnapshot): Boolean = snapshot.adverseState.isEmpty()
}
