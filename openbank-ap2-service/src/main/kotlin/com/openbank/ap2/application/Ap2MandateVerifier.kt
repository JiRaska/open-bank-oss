// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.application

import com.openbank.ap2.application.port.out.MandateKeyResolver
import com.openbank.ap2.application.port.out.SignatureVerifier
import com.openbank.ap2.domain.Ap2Mandate
import com.openbank.ap2.domain.ConstraintResult
import com.openbank.ap2.domain.MandateConstraintChecks
import com.openbank.ap2.domain.MandateVerdict
import com.openbank.ap2.domain.PresentedPayment
import com.openbank.ap2.domain.VerificationEvidence
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Orchestrates AP2 mandate verification (ADR-0193 §1): resolve the issuer key, verify the signature
 * chain, run the pure domain constraint check, and assemble a data-minimised evidence record. Both
 * stages must pass; either failure makes the verdict invalid and is recorded as an explicit reason.
 *
 * This class NEVER moves funds and never decides SCA exemption — it answers only "is this mandate
 * valid, and what does it authorize?" The verdict is evidence the SCA/payment path may later consult
 * (openbank-sca-service), gated by the HITL threshold, and only once a further ADR wires it in.
 */
@ApplicationScoped
class Ap2MandateVerifier(
    private val signatureVerifier: SignatureVerifier,
    private val keyResolver: MandateKeyResolver,
) {

    @Suppress("TooGenericExceptionCaught")
    fun verify(mandate: Ap2Mandate, payment: PresentedPayment): MandateVerdict {
        val failures = mutableListOf<String>()

        // Stage 1: signature chain against a TRUSTED issuer key (fail closed on either miss).
        val spki = keyResolver.resolve(mandate.issuer)
        val signatureValid = when {
            spki == null -> {
                failures.add("issuer not trusted: ${mandate.issuer}")
                false
            }
            else -> {
                val ok = try {
                    signatureVerifier.verify(mandate.algorithm, spki, mandate.signingInput, mandate.signatureB64)
                } catch (ex: Exception) {
                    // A malformed key/signature is a verification failure, never an exception out.
                    failures.add("signature verification error: ${ex.message}")
                    false
                }
                if (!ok && failures.isEmpty()) failures.add("signature invalid")
                ok
            }
        }

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
        return MandateVerdict(valid = signatureValid && constraintsSatisfied, evidence = evidence, failures = failures)
    }

    /** Data minimisation (ADR-0193 GDPR row): store the mandate hash, not the credential. */
    private fun sha256Hex(input: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))
}
