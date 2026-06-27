// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.copilot.domain

/** The kind of money-path action the customer is being asked to confirm. */
enum class ActionKind { PAYMENT, CARD_FREEZE, DISPUTE, FX_CONVERSION }

/**
 * A structured, validated proposal the assistant hands BACK to the app (ADR-0089 D2). The assistant
 * NEVER executes it: the app renders [summary] + [fields] as a non-AI-controlled action card and
 * routes it into the EXISTING customer-edge payment + SCA (dynamic-linking) flow, where the customer
 * confirms the exact amount and payee with a device-bound credential. [fields] holds the validated
 * parameters (fromAccountId, payeeIban, amount, currency, …) — authoritative, NOT the model's prose.
 */
data class ActionProposal(val kind: ActionKind, val summary: String, val fields: Map<String, String>)
