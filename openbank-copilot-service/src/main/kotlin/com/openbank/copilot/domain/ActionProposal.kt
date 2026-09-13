// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.domain

/** The kind of money-path action the customer is being asked to confirm. */
enum class ActionKind {
    PAYMENT,
    CARD_FREEZE,
    DISPUTE,
    FX_CONVERSION,

    /**
     * ADR-0269 rule 5, L2: a prepared loan application. Like every other kind here it is a DRAFT —
     * the assistant fills the form, the customer confirms it into the existing intake flow. The
     * proposal deliberately carries no rate and no instalment: a draft that claimed a price would
     * be a quote the bank never made.
     */
    CREDIT_APPLICATION,
}

/**
 * A structured, validated proposal the assistant hands BACK to the app (ADR-0089 D2). The assistant
 * NEVER executes it: the app renders [summary] + [fields] as a non-AI-controlled action card and
 * routes it into the EXISTING customer-edge payment + SCA (dynamic-linking) flow, where the customer
 * confirms the exact amount and payee with a device-bound credential. [fields] holds the validated
 * parameters (fromAccountId, payeeIban, amount, currency, …) — authoritative, NOT the model's prose.
 */
data class ActionProposal(val kind: ActionKind, val summary: String, val fields: Map<String, String>)
