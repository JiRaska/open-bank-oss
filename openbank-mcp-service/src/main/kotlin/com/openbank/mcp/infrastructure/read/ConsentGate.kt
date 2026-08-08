// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.openbank.mcp.application.port.out.ConsentContext
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * The single consent-validation call every real read adapter makes (extracted from
 * [RealAccountReadPort], which was the first caller — see its KDoc for the full rationale). Every
 * adapter behind [com.openbank.mcp.application.port.out.AccountReadPort] and the newer statement /
 * payment-confirmation ports call through here so the "live-validate, then intersect
 * `grantedAccounts`" rule cannot drift per adapter (a per-adapter copy is exactly the kind of thing
 * that quietly diverges the day one call site is edited and its siblings are not).
 */
@ApplicationScoped
class ConsentGate(@RestClient private val consent: ConsentValidateClient) {

    /**
     * Live-validates [consentContext]'s consent for [scope] (and, when given, that [accountIban] is
     * within its granted accounts). Throws (fails closed — never returns a partially-checked result)
     * when the consent is invalid, revoked, expired, or does not cover the requested account.
     */
    fun validate(consentContext: ConsentContext, scope: String, accountIban: String?): ConsentValidationResponse {
        val consentId = runCatching { UUID.fromString(consentContext.consentId) }.getOrElse {
            error("consent id '${consentContext.consentId}' is not a valid PSD2 consent id")
        }
        val response = consent.validate(
            consentId,
            ValidateConsentRequest(consentContext.agentId, scope, accountIban),
        )
        if (!response.valid) {
            error("consent denied: ${response.reason ?: response.code ?: "not valid"}")
        }
        val granted = response.grantedAccounts
        if (accountIban != null && granted != null && accountIban !in granted) {
            error("account '$accountIban' is not within the granted consent scope")
        }
        return response
    }
}
