// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.domain

/**
 * ADR-0269 rule 5 — what the assistant may do about credit, and on whose say-so.
 *
 * Three levels, each a separate consent, because one switch is either so coarse that consenting to
 * it means nothing or so frightening that nobody enables the genuinely useful middle one.
 *
 * The ordering is a containment hierarchy, not a preference: every level may do everything the
 * level below it may, and each adds exactly one new power. That is why [atLeast] is the only
 * comparison the tools use — a tool that checked `level == L1` would break the moment a customer
 * turned on L2.
 */
enum class CreditAiLevel {
    /**
     * Explainer. On by default, and safe as a default for one reason: it cannot speak first and it
     * never sees the customer's figures. It answers questions the customer asks about how credit
     * works — what APRC means, why an instalment is what it is, what early repayment does.
     */
    L0_EXPLAINER,

    /**
     * Advisor. Opt-in, and gated on `CREDIT_PROFILE_USE` because that is exactly what it does:
     * reads the ADR-0269 360 profile to answer "can I afford this" with its workings.
     *
     * The defining property is that it must be able to answer NO. An advisor that cannot decline is
     * not an advisor, and this level exists mainly so that "no, this would leave you with under a
     * month of cover" is a thing the bank's own assistant will say.
     */
    L1_ADVISOR,

    /**
     * Agent. Opt-in, gated on `CREDIT_AI_AGENT`. May watch and PREPARE — pre-fill an application,
     * scan for a cheaper refinancing, warn that an instalment looks at risk.
     *
     * It may never transition the origination state machine, accept an offer, raise a limit or draw
     * funds. Its output is a draft plus a proposal the customer confirms, which is the same
     * model-proposes/bank-disposes shape ADR-0089 D2 already uses for payments — reused rather than
     * reinvented, because the reason is identical: an AI that can move money is a different risk
     * class from one that can fill in a form.
     */
    L2_AGENT,
    ;

    fun atLeast(other: CreditAiLevel): Boolean = ordinal >= other.ordinal

    companion object {
        /**
         * The level a customer has, derived from their consents.
         *
         * Absence of consent is not an error and not a refusal — it is [L0_EXPLAINER], the level
         * everyone has. Deriving it here rather than at each call site means a new tool cannot
         * accidentally invent its own idea of what "no consent" implies.
         */
        fun from(profileUseConsent: Boolean, agentConsent: Boolean): CreditAiLevel = when {
            agentConsent -> L2_AGENT
            profileUseConsent -> L1_ADVISOR
            else -> L0_EXPLAINER
        }
    }
}
