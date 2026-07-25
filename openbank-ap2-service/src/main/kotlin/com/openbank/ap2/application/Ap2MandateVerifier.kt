// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.application

import com.openbank.ap2.application.port.out.Ap2MetricsPort
import com.openbank.ap2.application.port.out.MandateKeyResolver
import com.openbank.ap2.application.port.out.MandateSignatureOutcome
import com.openbank.ap2.application.port.out.SignatureVerifier
import com.openbank.ap2.domain.Ap2Mandate
import com.openbank.ap2.domain.ConstraintResult
import com.openbank.ap2.domain.MandateConstraintChecks
import com.openbank.ap2.domain.MandateVerdict
import com.openbank.ap2.domain.PresentedPayment
import com.openbank.ap2.domain.VerificationEvidence
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * Orchestrates AP2 mandate verification (ADR-0193 §1): resolve the issuer key, verify the signature
 * chain, run the pure domain constraint check, and assemble a data-minimised evidence record. Both
 * stages must pass; either failure makes the verdict invalid and is recorded as an explicit reason.
 *
 * This class NEVER moves funds and never decides SCA exemption — it answers only "is this mandate
 * valid, and what does it authorize?" The verdict is evidence the SCA/payment path may later consult
 * (openbank-sca-service), gated by the HITL threshold, and only once a further ADR wires it in.
 *
 * Both stages report to [Ap2MetricsPort] separately, because they fail for opposite reasons: a
 * signature-stage failure is usually **ours** (a rotated or mis-seeded trust list rejecting a
 * legitimate issuer, which looks exactly like a forgery on the wire), while a constraint failure is
 * **theirs** (a payment outside the delegated authority). The free-text `failures` list is for the
 * caller and the evidence record; it is never a metric tag — it embeds the issuer, which is
 * attacker-controlled.
 */
@ApplicationScoped
class Ap2MandateVerifier(
    private val signatureVerifier: SignatureVerifier,
    private val keyResolver: MandateKeyResolver,
    private val metrics: Ap2MetricsPort,
) {

    fun verify(mandate: Ap2Mandate, payment: PresentedPayment): MandateVerdict {
        val startedAt = System.nanoTime()
        val failures = mutableListOf<String>()

        // Stage 1: signature chain against a TRUSTED issuer key (fail closed on either miss).
        val signature = verifySignature(mandate, failures)
        val signatureValid = signature == MandateSignatureOutcome.VALID

        // Stage 2: pure constraint check (payee, amount cap, currency, expiry).
        val constraintsSatisfied = when (val cr = MandateConstraintChecks.check(mandate, payment)) {
            is ConstraintResult.Ok -> true
            is ConstraintResult.Violated -> {
                failures.addAll(cr.reasons)
                false
            }
        }

        val evidence = VerificationEvidence(
            mandateKind = mandate.kind,
            issuer = mandate.issuer,
            subject = mandate.subject,
            mandateHash = sha256Hex(mandate.signingInput),
            constraints = mandate.constraints,
            signatureValid = signatureValid,
            constraintsSatisfied = constraintsSatisfied,
        )
        val valid = signatureValid && constraintsSatisfied
        metrics.mandateVerified(
            kind = mandate.kind,
            signature = signature,
            constraintsSatisfied = constraintsSatisfied,
            valid = valid,
            duration = Duration.ofNanos(System.nanoTime() - startedAt),
        )
        return MandateVerdict(valid = valid, evidence = evidence, failures = failures)
    }

    /**
     * Classify the signature stage into a bounded outcome and append the human-readable reason.
     * `ISSUER_NOT_TRUSTED` is kept distinct from `INVALID` on purpose: the first is a trust-list
     * defect on our side, the second is a genuinely bad signature, and the HTTP response cannot tell
     * them apart.
     */
    @Suppress("TooGenericExceptionCaught") // any crypto/key malformation is a failed verdict, never a throw
    private fun verifySignature(mandate: Ap2Mandate, failures: MutableList<String>): MandateSignatureOutcome {
        val spki = keyResolver.resolve(mandate.issuer)
        if (spki == null) {
            failures.add("issuer not trusted: ${mandate.issuer}")
            return MandateSignatureOutcome.ISSUER_NOT_TRUSTED
        }
        return try {
            if (signatureVerifier.verify(mandate.algorithm, spki, mandate.signingInput, mandate.signatureB64)) {
                MandateSignatureOutcome.VALID
            } else {
                failures.add("signature invalid")
                MandateSignatureOutcome.INVALID
            }
        } catch (ex: Exception) {
            // A malformed key/signature is a verification failure, never an exception out.
            failures.add("signature verification error: ${ex.message}")
            MandateSignatureOutcome.VERIFICATION_ERROR
        }
    }

    /** Data minimisation (ADR-0193 GDPR row): store the mandate hash, not the credential. */
    private fun sha256Hex(input: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))
}
