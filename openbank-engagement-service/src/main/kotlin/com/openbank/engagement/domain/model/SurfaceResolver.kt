// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model

/**
 * ADR-0220 D1 minus ranking. The full design resolves a slot through
 * ContactPolicyGate (consent + frequency) → this eligibility exclusion → ADR-0201 NBA ranking →
 * one typed payload. NBA ranking is explicitly blocked on the model "graduating from shadow"
 * (D5), so this function returns every catalogue entry eligible for the slot, in catalogue order
 * — never none by omission, and never a fabricated ranking standing in for the real one.
 *
 * [ContactPolicyGate][com.openbank.libs.contact.ContactPolicyGate] (consent, frequency cap, quiet
 * hours) is NOT invoked here. It already exists in `openbank-libs-runtime` and is reused by
 * campaign-service — this domain layer cannot depend on it without crossing the domain/runtime
 * boundary ADR-0002 draws, so that composition is infrastructure's job (follow-up PR), not this
 * one's.
 */
object SurfaceResolver {
    fun resolve(slot: SurfaceSlot, eligibility: EligibilitySnapshot): List<SurfaceContent> =
        if (!EligibilityRule.isEligibleForPromotionalTargeting(eligibility)) {
            emptyList()
        } else {
            SurfaceCatalog.forSlot(slot)
        }
}
