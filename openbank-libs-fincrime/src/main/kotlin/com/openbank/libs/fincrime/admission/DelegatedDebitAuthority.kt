// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.admission

import java.time.OffsetDateTime
import java.util.UUID

/**
 * One delegation grant, as the [AdmissionStep.AUTHORITY] check needs to see it (ADR-0232 D1).
 *
 * Deliberately a flat projection rather than delegation-service's aggregate: this step must be
 * pure and local, so it is handed facts a rail already holds from the grant projection it consumes
 * (ADR-0232 D3) and never calls delegation-service on the money path, which that clause refuses.
 */
data class DelegationFact(
    val grantId: UUID,
    val grantorPartyId: UUID,
    val resourceId: UUID,
    val granteePartyId: UUID,
    val capabilities: Set<String>,
    val active: Boolean,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime?,
) {
    fun authorizes(accountId: UUID, actor: UUID, capability: String, now: OffsetDateTime): Boolean = active &&
        resourceId == accountId &&
        granteePartyId == actor &&
        capability in capabilities &&
        !now.isBefore(validFrom) &&
        (validTo == null || now.isBefore(validTo))
}

/** What the authority step is asked about. */
data class DebitAuthorityFacts(
    val accountId: UUID,
    val ownerPartyId: UUID,
    val actorPartyId: UUID,
    val requiredCapability: String,
    val grants: List<DelegationFact>,
    val now: OffsetDateTime,
)

/**
 * ADR-0375 D1 — the pure decision behind [AdmissionStep.AUTHORITY].
 *
 * Owner, or an ACTIVE grant over THIS account carrying the capability the action names. Three
 * things this shape is defending, each of them a defect this repo has already paid for:
 *
 *  - **The capability is an argument, never a default.** A rail asks for the capability its action
 *    needs. Accepting "any read capability" or falling back to the weakest one that would let the
 *    caller in is how `ACCOUNT_VIEW_DETAILS` came to be gated by `ACCOUNT_READ_BALANCES` and
 *    `CARD_MANAGE_STATUS` by `CARD_MANAGE_LIMITS` (ADR-0375 D7).
 *  - **No grant is not an outage.** A caller with no grant is [AdmissionDecision.REJECT], a
 *    definitive refusal. Only a rail that could not READ the grant state returns
 *    [StepOutcome.Unavailable], which the chain maps to HOLD for outbound money — the two are
 *    different facts and the fail-closed verdict must not blur them, as the ownership gate's
 *    "could not be established" once hid a plain 401.
 *  - **It decides authority only.** Whether the grant's ceilings allow THIS amount is
 *    [AdmissionStep.LIMITS], whose counter lives in one authoritative place (ADR-0249 D3). A
 *    delegate inside their ceiling with no grant is refused here; a delegate with a grant over
 *    their ceiling is refused there. Answering both here would put a second limit counter in the
 *    chain, which ADR-0249 rejected by name.
 */
object DelegatedDebitAuthority {

    val OWNER: AdmissionReasonCode = AdmissionReasonCode("AUTHORITY_OWNER")
    val DELEGATED: AdmissionReasonCode = AdmissionReasonCode("AUTHORITY_DELEGATED")
    val NO_GRANT: AdmissionReasonCode = AdmissionReasonCode("AUTHORITY_NO_GRANT")

    fun evaluate(facts: DebitAuthorityFacts): StepOutcome {
        if (facts.actorPartyId == facts.ownerPartyId) {
            return StepOutcome.Decided(AdmissionDecision.ALLOW, OWNER)
        }
        val granted = facts.grants.firstOrNull {
            it.authorizes(facts.accountId, facts.actorPartyId, facts.requiredCapability, facts.now)
        }
        return if (granted != null) {
            // The grant is named in the evidence, not merely counted. A delegated debit is only
            // auditable AS delegated if the record says which grant permitted it, and a grant is
            // revocable — so nothing after the fact can reconstruct which one was live. Picking
            // the first permitting grant rather than asking "any?" is what makes that possible.
            StepOutcome.Decided(
                AdmissionDecision.ALLOW,
                DELEGATED,
                AdmissionEvidence.of(
                    "capability" to facts.requiredCapability,
                    "grant_id" to granted.grantId.toString(),
                    "grantor_party_id" to granted.grantorPartyId.toString(),
                ),
            )
        } else {
            StepOutcome.Decided(
                AdmissionDecision.REJECT,
                NO_GRANT,
                AdmissionEvidence.of("capability" to facts.requiredCapability),
            )
        }
    }

    /** Binds [evaluate] as the chain's authority check; the rail supplies the facts. */
    fun check(): AdmissionCheck<DebitAuthorityFacts> = AdmissionCheck { facts, _ -> evaluate(facts) }
}
