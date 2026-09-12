// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * A human's confirmation of how one entity is represented, keyed by identifier (issue #9711).
 *
 * [CzechRepresentationRuleParser][com.openbank.kyb.domain.czech.CzechRepresentationRuleParser] is a
 * heuristic over free text. It is built so the dangerous verdict is the guarded one — SOLE is
 * withheld whenever any second-person, condition, amount or negation marker is present — but that
 * guarantees only that a text carrying a *known* marker cannot read as solo. A phrasing whose
 * second-signature clause uses none of them would still parse SOLE, and nothing downstream could
 * tell. So no machine verdict binds an agreement on its own: an operator confirms it once per
 * entity, and that confirmation is what the case uses from then on.
 *
 * ## Why the text is hashed into the key
 *
 * An attestation is a statement about a *rule*, not about a company. A company that changes its
 * způsob jednání — the single event this control exists for, since it is exactly when the old
 * signature count becomes wrong — keeps its IČO. Binding the attestation to the identifier alone
 * would silently carry yesterday's confirmation onto today's different rule, which is the failure
 * this whole feature is meant to prevent, arriving through the control itself.
 *
 * [ruleTextHash] is therefore part of the identity: it is derived from the register's own text after
 * the parser's own normalisation (case, diacritics and whitespace folded), so a re-fetch that
 * differs only in spacing still matches, and a genuine amendment does not. A rule with no text at
 * all (a register that publishes none) hashes the empty string and is attestable like any other —
 * what it cannot do is silently match a *different* absent text, because there is only one.
 */
data class RepresentationAttestation(
    val id: UUID,
    val identifier: LegalEntityIdentifier,
    /** Normalised hash of the register text this attestation is ABOUT; see the class KDoc. */
    val ruleTextHash: String,
    /** The register text as fetched, kept verbatim for the audit trail and for the next reviewer. */
    val ruleText: String?,
    /** What the parser said when the operator was asked — recorded to measure the parser, never to gate on. */
    val parsedMode: RepresentationMode,
    val parsedSigners: Int?,
    /** What the human decided. This is what binds. */
    val confirmedSigners: Int,
    /** The offices that must sign, when the operator says the rule names them. Empty = any signatories. */
    val confirmedRoles: List<String>,
    val attestedBy: String,
    val attestedAt: Instant,
    /** Set when a later attestation supersedes this one; a superseded row is never used for a decision. */
    val supersededAt: Instant? = null,
    val note: String? = null,
) {
    init {
        require(confirmedSigners >= 1) { "at least one signature is required" }
        require(attestedBy.isNotBlank()) { "an attestation must name who made it" }
    }

    val isActive: Boolean get() = supersededAt == null

    /** True when the operator's decision differs from what the parser produced — the measurement that matters. */
    val correctedTheParser: Boolean
        get() = parsedSigners != confirmedSigners ||
            confirmedRoles.isNotEmpty() != (parsedMode == RepresentationMode.UNKNOWN)

    companion object {

        /**
         * The identity of a rule TEXT. Folded exactly as the parser folds it, so a re-fetch that
         * differs only in case, diacritics or whitespace is the same rule; anything else is not.
         */
        fun hashOf(ruleText: String?): String {
            val folded = com.openbank.kyb.domain.czech.CzechRepresentationRuleParser.fold(ruleText.orEmpty())
            val digest = MessageDigest.getInstance("SHA-256").digest(folded.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * What an extract's representation means for a case once attestations are consulted.
 *
 * Three outcomes, deliberately not two: an [Attested] rule proceeds on the human's numbers, an
 * [Unattested] one goes to review carrying the parser's opinion as a SUGGESTION for the operator,
 * and [Superseded] is the case that would otherwise be invisible — the entity HAS been attested,
 * and the register text has since changed, so the old confirmation must not be reused and the
 * reviewer needs to be told that rather than shown a blank form.
 */
sealed interface RepresentationDecision {
    data class Attested(val attestation: RepresentationAttestation) : RepresentationDecision
    data class Unattested(val rule: RepresentationRule) : RepresentationDecision
    data class Superseded(val previous: RepresentationAttestation, val rule: RepresentationRule) :
        RepresentationDecision
}
