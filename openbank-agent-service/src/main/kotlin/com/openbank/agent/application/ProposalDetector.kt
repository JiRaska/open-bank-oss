// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

/**
 * Detects whether an assistant reply constitutes a *proposal* — a recommended action that
 * requires human review before execution (ADR-0031 D4, ui-assistant charter
 * `requires_human: every proposal`).
 *
 * The ui-assistant is read-only and never executes actions itself, so "proposal" means a
 * textual recommendation that an operator should explicitly confirm before acting on it.
 * Flagging the reply lets the admin-UI render proposals with a distinct visual treatment
 * (e.g. a confirmation chip, audit trail, or approval button) so HITL is enforced in the UX
 * layer rather than silently buried in chat text.
 *
 * Detection is intentionally conservative: it only fires on explicit imperative phrases that
 * suggest a recommended action (not on informational answers). False negatives are acceptable;
 * false positives (treating a plain read response as a proposal) must be avoided to prevent
 * alert fatigue.
 */
object ProposalDetector {

    // Imperative patterns that indicate a recommended action requiring human decision.
    // Ordered from most-specific to least-specific to reduce false positives.
    // Conservative patterns: only explicit addressee + imperative verb directed AT the operator.
    // `consider` and `you could` are deliberately excluded — they appear in factual banking replies
    // ("consider that the balance includes a hold…") and would cause alert fatigue.
    private val PROPOSAL_PATTERNS = listOf(
        // "I recommend/suggest (that you)…" — explicit first-person recommendation
        Regex("""(?i)\bI (recommend|suggest)\b.{5,}"""),
        // "you should …" — direct imperative to the operator
        Regex("""(?i)\byou should\b.{5,}"""),
        // "it is recommended / it would be best …" — formal recommendation phrases
        Regex("""(?i)\b(it is recommended|advisable|it would be best)\b.{5,}"""),
        // "please [investigate|escalate|block|…]" — explicit action request
        Regex("""(?i)\bplease (review|investigate|verify|approve|escalate|block|close|flag|freeze|suspend)\b"""),
        // "action required/needed" — standard banking alert phrase
        Regex("""(?i)\baction (required|needed)\b"""),
        // "[account/user] should be [investigated|escalated|blocked|…]" — passive-voice recommendation
        Regex(
            """(?i)\b(account|customer|transaction|user|payment)\b.{0,30}""" +
                """\bshould be (investigated|reviewed|escalated|blocked|closed|flagged|frozen|suspended)\b""",
        ),
    )

    /**
     * Returns true when [reply] contains language that indicates a proposal / recommended action.
     * Pure factual read responses (account details, transaction lists) are NOT proposals.
     */
    fun isProposal(reply: String): Boolean = PROPOSAL_PATTERNS.any { it.containsMatchIn(reply) }
}
