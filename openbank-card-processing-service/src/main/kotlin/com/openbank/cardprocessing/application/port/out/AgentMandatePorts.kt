// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.cardprocessing.application.port.out

import java.time.Instant

/**
 * An AP2 mandate as an acquirer presents it with an agent-initiated authorisation (ADR-0283 D6,
 * ADR-0193).
 *
 * The fields are the ones ap2-service's verifier needs and nothing more. Card-processing does not
 * parse the wire format, does not check the signature and holds no trust list — it forwards what it
 * was given and acts on the verdict. Putting a second verifier here would be a second opinion about
 * whether an agent may spend, and the two would eventually disagree.
 *
 * [agentId] is the acting agent's own identity, forwarded so ap2-service authorises the call as
 * that agent rather than as this service. It is deliberately NOT card-processing's identity: the
 * mandate belongs to the agent, and attributing the verification to the bank's own service account
 * would make every agent's calls indistinguishable in the audit trail.
 */
data class PresentedMandate(
    val kind: String,
    val issuer: String,
    val subject: String,
    val signingInput: String,
    val signatureB64: String,
    val algorithm: String,
    val payee: String,
    val amountCapMinorUnits: Long,
    val currency: String,
    val expiresAt: Instant,
    val singleUse: Boolean,
    val agentId: String?,
)

/**
 * What the verification established. Three values, not a boolean, and the third is the point.
 *
 * `REJECTED` means ap2-service answered and the mandate does not authorise this payment — a wrong
 * payee, an expired mandate, an amount over the cap, a signature that does not verify. `UNVERIFIABLE`
 * means nobody answered: the service was unreachable, the policy denied the call, or the response
 * could not be read. Both decline the authorisation, and they are opposite operational facts — one
 * is an agent exceeding its authority, the other is this bank being unable to tell. Collapsing them
 * into `valid = false` is the same mistake as a skipped delivery sharing a flag with a real one
 * (ADR-0252 phase 0, #4348).
 */
enum class MandateOutcome { VERIFIED, REJECTED, UNVERIFIABLE }

/** [failures] is ap2-service's own list, carried verbatim so the decline reason can quote it. */
data class MandateVerification(
    val outcome: MandateOutcome,
    val failures: List<String> = emptyList(),
    val detail: String? = null,
)

/**
 * Verification of an agent's authority to make THIS payment.
 *
 * Fails CLOSED by construction: the adapter returns `UNVERIFIABLE` rather than throwing, and the
 * use case declines on anything but `VERIFIED`. The opposite — proceeding when the verifier is
 * down — would let an agent-initiated payment through on the strength of an unread mandate, which
 * is the one outcome an agentic-commerce path must never have.
 */
interface AgentMandatePort {
    suspend fun verify(
        mandate: PresentedMandate,
        amountMinorUnits: Long,
        currencyCode: String,
        payee: String,
        at: Instant,
    ): MandateVerification
}
