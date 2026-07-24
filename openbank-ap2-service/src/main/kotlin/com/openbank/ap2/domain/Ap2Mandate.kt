// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.domain

import java.time.Instant

/**
 * The AP2 (Agent Payments Protocol) mandate model and its PURE constraint logic (ADR-0193). This
 * file is the domain layer — it holds ZERO framework and ZERO crypto: signature verification is a
 * port ([com.openbank.ap2.application.port.out.SignatureVerifier]); here we model the mandate and
 * decide whether the presented payment falls inside the mandate's declared authority.
 *
 * AP2 defines three signed mandates that narrow from a standing authority to one transaction:
 * INTENT (the customer's standing authority for an agent to transact within limits) → CART → PAYMENT
 * (this specific transaction). Bank-side we verify a presented mandate as authorization evidence; we
 * never mint one and, under ADR-0193, never move funds on one.
 */
enum class MandateKind { INTENT, CART, PAYMENT }

/** The signature scheme over the mandate's signing input. Covers the schemes AP2 VCs use in practice. */
enum class MandateSignatureAlgorithm { ES256, ED25519 }

/**
 * The limits the mandate binds the delegated authority to. A payment outside ANY of these is outside
 * the mandate — the bank must not treat it as authorized (ADR-0193 §1 constraint stage).
 */
data class MandateConstraints(
    val payee: String,
    val amountCapMinor: Long,
    val currency: String,
    val expiresAt: Instant,
    val singleUse: Boolean = false,
)

/**
 * A presented AP2 mandate. [signingInput] is the exact string that was signed (for a JWS VC, the
 * base64url `header.payload`); [signatureB64] is its signature. The domain never parses the wire
 * format — an adapter hands it these fields — so the JOSE/VC encoding lives entirely at the edge.
 */
data class Ap2Mandate(
    val kind: MandateKind,
    val issuer: String,
    val subject: String,
    val constraints: MandateConstraints,
    val signingInput: String,
    val signatureB64: String,
    val algorithm: MandateSignatureAlgorithm,
)

/** The concrete payment the mandate is being presented to authorize. */
data class PresentedPayment(val payee: String, val amountMinor: Long, val currency: String, val at: Instant)

/** Outcome of the pure constraint stage. Violations are explicit so the evidence records WHY. */
sealed interface ConstraintResult {
    data object Ok : ConstraintResult
    data class Violated(val reasons: List<String>) : ConstraintResult
}

/**
 * The pure constraint check: does [payment] fall inside [mandate]'s declared authority? No crypto,
 * no I/O, no framework — deterministic and unit-proven. A failure is fail-closed by construction:
 * the caller treats anything but [ConstraintResult.Ok] as "not authorized".
 */
object MandateConstraintChecks {
    fun check(mandate: Ap2Mandate, payment: PresentedPayment): ConstraintResult {
        val c = mandate.constraints
        val reasons = buildList {
            if (payment.at.isAfter(c.expiresAt)) {
                add("mandate expired at ${c.expiresAt}")
            }
            if (payment.amountMinor > c.amountCapMinor) {
                add("amount ${payment.amountMinor} exceeds cap ${c.amountCapMinor}")
            }
            if (!payment.currency.equals(c.currency, ignoreCase = true)) {
                add("currency ${payment.currency} does not match mandate ${c.currency}")
            }
            if (payment.payee != c.payee) {
                add("payee ${payment.payee} does not match mandate ${c.payee}")
            }
        }
        return if (reasons.isEmpty()) ConstraintResult.Ok else ConstraintResult.Violated(reasons)
    }
}

/**
 * The authorization-evidence record produced by verification (ADR-0193 §2). Data-minimising: it
 * carries the mandate HASH, not the full credential (GDPR row in the ADR). This is the record the
 * SCA decision (openbank-sca-service) may later consult; the verifier never decides exemption itself.
 */
data class VerificationEvidence(
    val mandateKind: MandateKind,
    val issuer: String,
    val subject: String,
    val mandateHash: String,
    val constraints: MandateConstraints,
    val signatureValid: Boolean,
    val constraintsSatisfied: Boolean,
)

/** The verifier's verdict: valid iff BOTH the signature chain and the constraints passed. */
data class MandateVerdict(val valid: Boolean, val evidence: VerificationEvidence, val failures: List<String>)
