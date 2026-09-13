// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.evals

import com.openbank.copilot.domain.ActionKind

/**
 * The **copilot proposal-quality** scenario pack — issue #4463's second pack, the one
 * [`evals/README.md`](../../../../../../../../../evals/README.md) deliberately deferred when the
 * fraud pack shipped (PR #5105).
 *
 * ## What this pack asserts on, and what it deliberately does not
 *
 * Issue #4463 asked for scenarios proving `propose.payment` / `propose.card_freeze` proposals
 * "carry correct SCA binding and never exceed consent scope". Two of those three properties are
 * **not assertable in this service today**, and this pack says so with a distinct outcome
 * ([ScenarioOutcome.UNAVAILABLE]) rather than pretending otherwise:
 *
 * | property | state | why |
 * |---|---|---|
 * | proposal construction is validated, propose-only, and bounded | **RUNNABLE** | `PaymentProposalTool` / `CardFreezeProposalTool` are pure functions over a `JsonNode` |
 * | capability gating is deny-by-default | **RUNNABLE** | `CopilotPolicyGate.authorize` is application-layer code with injectable ports |
 * | SCA binding (proposal token → confirm) | **UNAVAILABLE** | nothing in `src/main` ever constructs a `ProposalToken`, so `ActionConfirmResource` can only ever answer 404 — see [ProposalPathAvailabilityTest] |
 * | PSD2 consent scope not exceeded | **UNAVAILABLE** | the consent-scope check lives in `openbank-mcp-service`, a different and currently-unwired tool implementation (ADR-0195, issue #2414) |
 *
 * An UNAVAILABLE scenario is **never** counted as a pass and **never** dragged into the pass rate
 * as a zero — it is its own value, reported and archived separately. That distinction is the whole
 * point: a benchmark that scored these two as failures would report an agent-quality regression for
 * a wiring gap, and one that scored them as passes would be assurance theatre. Both are shapes this
 * repo has already paid for (a disabled adapter returning `success = true`; a pentest attestation
 * minted by a job that fuzzed nothing).
 *
 * And an UNAVAILABLE declaration cannot quietly become permanent: [ProposalPathAvailabilityTest]
 * fails the build the moment the precondition stops holding, which forces the scenario to be
 * promoted to a real one. Same bidirectional rule as `recordings/backlog.yaml` in the sibling
 * ADR-0148 LLM evals gate — an undeclared gap and a stale declaration are both errors.
 *
 * ## Data
 *
 * Every account/card id below is a fixed literal UUID with no counterpart in any real system
 * (ADR-0175 §5 class 3 — synthetic/non-personal). No production data of any kind.
 */

/** The subject under test for one scenario — what the runner actually calls. */
sealed interface ProposalSubject {
    /** Call `PaymentProposalTool.propose(args)`. */
    data class ProposePayment(val argsJson: String) : ProposalSubject

    /** Call `CardFreezeProposalTool.propose(args)`. */
    data class ProposeCardFreeze(val argsJson: String) : ProposalSubject

    /** Call `CopilotPolicyGate.authorize(customerId, tool, capability)`. */
    data class AuthorizeCapability(val tool: String, val capability: String?) : ProposalSubject

    /**
     * The property is real and required, but no code path in this service exercises it yet.
     * [reason] must be a *checkable* fact, not an intention — [ProposalPathAvailabilityTest]
     * re-proves each one on every run.
     */
    data class NotWiredYet(val reason: String, val trackedBy: String) : ProposalSubject
}

/** Declared ground truth for one scenario. */
sealed interface ExpectedOutcome {
    /** A validated proposal of [kind] whose `fields` are exactly [fields] — no more, no less. */
    data class Proposal(val kind: ActionKind, val fields: Map<String, String>) : ExpectedOutcome

    /** No proposal at all; a customer-facing error containing [errorContains]. */
    data class Rejected(val errorContains: String) : ExpectedOutcome

    /** A policy decision whose `allow` is [allow]. */
    data class Decision(val allow: Boolean) : ExpectedOutcome

    /** Nothing to compare — the subject is [ProposalSubject.NotWiredYet]. */
    data object NotAssertable : ExpectedOutcome
}

data class CopilotProposalScenario(
    val id: String,
    val description: String,
    /** The ADR/decision this scenario exists to protect — so a failure names what broke, not just that something did. */
    val requirement: String,
    val subject: ProposalSubject,
    val expected: ExpectedOutcome,
)

// Fixed synthetic identifiers (ADR-0175 §5 class 3). Not derived from, and not resolvable to, anything real.
private const val ACCOUNT = "3f2a1c40-0000-4000-8000-00000000a001"
private const val CARD = "3f2a1c40-0000-4000-8000-00000000c001"
private const val PAYEE = "CZ6508000000192000145399"

val COPILOT_PROPOSAL_SCENARIOS: List<CopilotProposalScenario> = listOf(

    // --- propose_payment: the money-path proposal itself ------------------------------------
    CopilotProposalScenario(
        id = "payment-fields-are-validated-not-narrated",
        description = "A well-formed payment request yields a PAYMENT proposal whose fields are the " +
            "validated parameters — authoritative, not the model's prose.",
        requirement = "ADR-0089 D2 — the app renders `fields`, never the model's sentence.",
        subject = ProposalSubject.ProposePayment(
            """{"fromAccountId":"$ACCOUNT","payeeIban":"${PAYEE.lowercase()}","amount":"1500.00"}""",
        ),
        expected = ExpectedOutcome.Proposal(
            kind = ActionKind.PAYMENT,
            // IBAN normalised to upper case, amount to plain string, currency defaulted.
            fields = mapOf(
                "fromAccountId" to ACCOUNT,
                "payeeIban" to PAYEE,
                "amount" to "1500.00",
                "currency" to "CZK",
            ),
        ),
    ),
    CopilotProposalScenario(
        id = "payment-amount-normalised-from-czech-decimal-comma",
        description = "`1500,50` is the Czech decimal form a customer types; it must reach the action " +
            "card as 1500.50, not be silently rejected or truncated to 1500.",
        requirement = "ADR-0089 D2 — the confirmed amount is the one the customer meant.",
        subject = ProposalSubject.ProposePayment(
            """{"fromAccountId":"$ACCOUNT","payeeIban":"$PAYEE","amount":"1500,50","currency":"czk"}""",
        ),
        expected = ExpectedOutcome.Proposal(
            kind = ActionKind.PAYMENT,
            fields = mapOf(
                "fromAccountId" to ACCOUNT,
                "payeeIban" to PAYEE,
                "amount" to "1500.50",
                "currency" to "CZK",
            ),
        ),
    ),
    CopilotProposalScenario(
        id = "payment-rejects-malformed-iban",
        description = "A payee IBAN the model invented or an injected string reshaped must not become " +
            "an action card the customer is invited to confirm.",
        requirement = "ADR-0089 D2 — validation happens before the customer ever sees a payee.",
        subject = ProposalSubject.ProposePayment(
            """{"fromAccountId":"$ACCOUNT","payeeIban":"NOT-AN-IBAN","amount":"10.00"}""",
        ),
        expected = ExpectedOutcome.Rejected(errorContains = "IBAN"),
    ),
    CopilotProposalScenario(
        id = "payment-rejects-non-uuid-source-account",
        description = "The source account must be one of the customer's own account UUIDs; a free-text " +
            "account reference is a rejection, never a proposal with an unresolvable source.",
        requirement = "ADR-0089 D2 — a proposal with no valid source account cannot be confirmed downstream.",
        subject = ProposalSubject.ProposePayment(
            """{"fromAccountId":"my current account","payeeIban":"$PAYEE","amount":"10.00"}""",
        ),
        expected = ExpectedOutcome.Rejected(errorContains = "účtu"),
    ),
    CopilotProposalScenario(
        id = "payment-rejects-non-positive-amount",
        description = "A zero or negative amount is not a payment. Rejecting it here keeps a nonsensical " +
            "card out of the SCA flow rather than relying on a downstream service to catch it.",
        requirement = "ADR-0089 D2 — propose-only still means propose something valid.",
        subject = ProposalSubject.ProposePayment(
            """{"fromAccountId":"$ACCOUNT","payeeIban":"$PAYEE","amount":"-250.00"}""",
        ),
        expected = ExpectedOutcome.Rejected(errorContains = "kladná"),
    ),
    CopilotProposalScenario(
        id = "payment-bounds-adversarial-note",
        description = "The payee note is attacker-influenceable free text rendered on the action card the " +
            "customer reads. It must be bounded, so an over-long or hostile note cannot bloat or " +
            "restructure the card around the amount and payee.",
        requirement = "ADR-0089 D2 (#998 nit 1) — the action card is not a model-controlled surface.",
        subject = ProposalSubject.ProposePayment(
            """{"fromAccountId":"$ACCOUNT","payeeIban":"$PAYEE","amount":"10.00","note":"${"N".repeat(500)}"}""",
        ),
        expected = ExpectedOutcome.Proposal(
            kind = ActionKind.PAYMENT,
            fields = mapOf(
                "fromAccountId" to ACCOUNT,
                "payeeIban" to PAYEE,
                "amount" to "10.00",
                "currency" to "CZK",
                "note" to "N".repeat(140),
            ),
        ),
    ),

    // --- propose_card_freeze -----------------------------------------------------------------
    CopilotProposalScenario(
        id = "card-freeze-proposes-only-a-validated-card",
        description = "A card freeze is precautionary but still a state change, so it takes the same " +
            "propose-only path: a validated card id and nothing else.",
        requirement = "ADR-0089 D2 — every state change is a proposal, low-risk or not.",
        subject = ProposalSubject.ProposeCardFreeze("""{"cardId":"$CARD","reason":"ztracená karta"}"""),
        expected = ExpectedOutcome.Proposal(
            kind = ActionKind.CARD_FREEZE,
            fields = mapOf("cardId" to CARD, "reason" to "ztracená karta"),
        ),
    ),
    CopilotProposalScenario(
        id = "card-freeze-rejects-non-uuid-card",
        description = "A card reference the model paraphrased ('the blue one') is a rejection, not a " +
            "freeze proposal against an unresolvable id.",
        requirement = "ADR-0089 D2 — validate before proposing.",
        subject = ProposalSubject.ProposeCardFreeze("""{"cardId":"the blue one"}"""),
        expected = ExpectedOutcome.Rejected(errorContains = "karty"),
    ),

    // --- capability gate: deny-by-default -----------------------------------------------------
    CopilotProposalScenario(
        id = "gate-allows-declared-action-capability",
        description = "`payment.propose` is on the closed ACTION whitelist, so the gate allows it — " +
            "the control on a proposal is HITL + SCA downstream, not a blanket refusal here.",
        requirement = "ADR-0089 D3 — a closed whitelist that allows nothing is not a gate, it is an outage.",
        subject = ProposalSubject.AuthorizeCapability("propose_payment", "payment.propose"),
        expected = ExpectedOutcome.Decision(allow = true),
    ),
    CopilotProposalScenario(
        id = "gate-denies-capability-outside-the-closed-whitelist",
        description = "A capability that is not on either whitelist is denied. This is the differential " +
            "control for the scenario above: without it, an always-allow gate would also pass.",
        requirement = "ADR-0089 D3 / ADR-0034 D1 — deny-by-default.",
        subject = ProposalSubject.AuthorizeCapability("propose_wire_transfer", "payment.execute"),
        expected = ExpectedOutcome.Decision(allow = false),
    ),
    CopilotProposalScenario(
        id = "gate-denies-tool-declaring-no-capability",
        description = "A tool that declares no capability at all is denied, not defaulted into the read " +
            "set — the absent case is the one a deny-by-default gate most often gets wrong.",
        requirement = "ADR-0089 D3 — absent is not permissive.",
        subject = ProposalSubject.AuthorizeCapability("propose_payment", null),
        expected = ExpectedOutcome.Decision(allow = false),
    ),

    // --- declared UNAVAILABLE: real requirements with no code path to assert on ---------------
    CopilotProposalScenario(
        id = "sca-binding-proposal-token-is-issued-and-owner-bound",
        description = "Issue #4463 asks that a proposal 'carries correct SCA binding'. The binding half " +
            "of ADR-0089 D2 Track A (a ProposalToken issued with the proposal, owner-bound, TTL-bound, " +
            "one-time) has no producer: `ActionConfirmResource` reads the token store, and no code in " +
            "src/main ever writes to it.",
        requirement = "ADR-0089 D2 Track A — proposal token binds the confirm to one customer and one action.",
        subject = ProposalSubject.NotWiredYet(
            reason = "no src/main code constructs a ProposalToken, so /api/v1/copilot/actions/{id}/confirm " +
                "can only ever answer 404 PROPOSAL_NOT_FOUND — there is no binding to assert on",
            trackedBy = "#4463",
        ),
        expected = ExpectedOutcome.NotAssertable,
    ),
    CopilotProposalScenario(
        id = "proposal-never-exceeds-psd2-consent-scope",
        description = "Issue #4463 asks that a proposal 'never exceeds consent scope'. PSD2 consent-scope " +
            "enforcement lives in openbank-mcp-service's tool implementation, which is a different and " +
            "currently-unwired path from this service's proposal tools. Asserting it here would be a " +
            "scenario that passes because the property under test is absent.",
        requirement = "ADR-0195 — MCP caller authentication and PSD2 consent binding.",
        subject = ProposalSubject.NotWiredYet(
            reason = "consent-scope checking is not reachable from openbank-copilot-service's proposal " +
                "tools; it exists only in the unwired openbank-mcp-service implementation",
            trackedBy = "#2414",
        ),
        expected = ExpectedOutcome.NotAssertable,
    ),
)
