// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.application.port.out

import com.openbank.ap2.domain.MandateSignatureAlgorithm

/**
 * The two ports the mandate verifier delegates to (ADR-0193 §1). Keeping crypto and key resolution
 * behind ports is what lets the domain constraint logic stay pure and the signature primitive stay
 * the JDK JCA one openbank-sca-service already trusts.
 */

/** Verifies a signature over [signingInput] with the issuer's X.509 SPKI public key. */
interface SignatureVerifier {
    fun verify(
        algorithm: MandateSignatureAlgorithm,
        publicKeySpkiB64: String,
        signingInput: String,
        signatureB64: String,
    ): Boolean
}

/**
 * Resolves a mandate issuer to its trusted public key (X.509 SubjectPublicKeyInfo, base64), or null
 * if the issuer is not trusted. Phase 1 resolves against a configured trust list; the DID /
 * issuer-registry resolver is phase 2 behind this same port (ADR-0193 §1). An unresolved issuer is a
 * closed failure — the verifier never trusts a key it cannot anchor.
 */
interface MandateKeyResolver {
    fun resolve(issuer: String): String?
}
