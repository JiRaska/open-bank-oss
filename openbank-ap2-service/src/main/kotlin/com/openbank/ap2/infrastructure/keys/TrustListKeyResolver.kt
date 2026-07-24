// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.infrastructure.keys

import com.openbank.ap2.application.port.out.MandateKeyResolver
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * Phase-1 issuer key resolution (ADR-0193 §1): a configured, deliberately small trust list. The
 * config value is `issuer1=<spkiB64>;issuer2=<spkiB64>` (semicolon-separated, `=`-keyed). An issuer
 * absent from the list resolves to null, which the verifier treats as a closed failure — the phase-1
 * verifier never trusts a key it cannot anchor to this list.
 *
 * Phase 2 replaces this with a DID / issuer-registry resolver behind the same [MandateKeyResolver]
 * port; the verifier and the endpoint do not change.
 */
@ApplicationScoped
class TrustListKeyResolver(@ConfigProperty(name = "ap2.trust-list") trustList: Optional<String>) : MandateKeyResolver {

    private val keys: Map<String, String> = parse(trustList.orElse(""))

    override fun resolve(issuer: String): String? = keys[issuer]

    private fun parse(raw: String): Map<String, String> = raw
        .split(';')
        .map { it.trim() }
        .filter { it.contains('=') }
        .associate { entry ->
            val idx = entry.indexOf('=')
            entry.substring(0, idx).trim() to entry.substring(idx + 1).trim()
        }
}
