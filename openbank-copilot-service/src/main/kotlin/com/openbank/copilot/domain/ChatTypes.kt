// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.domain

/**
 * Domain types for the customer assistant (ADR-0089). Framework-free (ADR-0002).
 *
 * The model is a router + narrator, never an authority: a [ChatReply] only ever narrates figures
 * that came from a tool result, and any state-changing intent becomes a *proposal* gated by
 * human-in-the-loop + SCA (ADR-0089 D2) — never an action emitted from here.
 */

/** One customer turn. [message] is untrusted input — never treated as instructions (ADR-0089 D3). */
data class ChatTurn(val conversationId: String, val message: String)

/**
 * A narrated reply. Figures, if any, are rendered from tool results — not model generation. When the
 * turn produced a money-path action, [proposal] carries the structured, validated proposal the app
 * renders as a non-AI-controlled card and routes into the existing edge payment + SCA flow (D2).
 */
data class ChatReply(val conversationId: String, val reply: String, val proposal: ActionProposal? = null)

/** Outcome of handling a turn. */
sealed interface ChatOutcome {
    /** Capability is gated off (feature flag / Phase-1 skeleton, ADR-0067). */
    data object Disabled : ChatOutcome

    /** A reply was produced. */
    data class Replied(val reply: ChatReply) : ChatOutcome
}
