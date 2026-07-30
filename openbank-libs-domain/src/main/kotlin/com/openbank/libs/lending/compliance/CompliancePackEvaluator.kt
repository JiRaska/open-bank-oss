// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.compliance

import com.openbank.libs.decision.PolicyRule
import com.openbank.libs.lending.origination.OriginationState

/**
 * Pure read-side queries over a compiled pack (ADR-0212 D5): given the pack, what
 * steps, disclosures and limits apply. The evaluator never decides legality on its
 * own — it exposes what the pinned pack declares, so the origination state machine,
 * the policy engine (ADR-0213) and the termination lifecycle (ADR-0215) all read one
 * source of legal truth.
 */
object CompliancePackEvaluator {

    /** States the origination graph may skip by default but this pack makes mandatory. */
    fun mandatorySteps(pack: CompiledCompliancePack): Set<OriginationState> = pack.pack.requiredSteps

    fun disclosuresFor(pack: CompiledCompliancePack, stage: DisclosureStage): List<PackDisclosure> =
        pack.pack.disclosures.filter { it.stage == stage }

    /**
     * Jurisdiction-mandated credit checks as ADR-0213 eligibility rules: the service
     * appends them to the ELIGIBILITY table at evaluation time, so a statutory floor
     * (e.g. DSTI cap from the local act) is enforced by the same fail-closed engine
     * as commercial policy — never bypassed, never silently absent.
     */
    fun mandatoryEligibilityRules(pack: CompiledCompliancePack): List<PolicyRule> = pack.pack.mandatoryChecks

    fun coolingOffDays(pack: CompiledCompliancePack): Int = pack.pack.coolingOffDays

    fun terminationRules(pack: CompiledCompliancePack): TerminationRules = pack.pack.terminationRules
}
