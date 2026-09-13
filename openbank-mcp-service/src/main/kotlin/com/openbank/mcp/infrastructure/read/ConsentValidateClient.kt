// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * `POST /api/v1/consents/{id}/validate` (consent-service, ADR-0126) — the single source of truth
 * for what a presented consent grants (ADR-0195). [OidcClientRequestReactiveFilter] attaches a
 * client-credentials M2M token (same pattern as agent-service's downstream clients), since the
 * endpoint is itself `@Authorize(action = "consent.validate")`. NOT wired as the default consent
 * source yet: mcp-service has no provisioned M2M OIDC client / rego grant for `consent.validate`
 * today — that infra lands with OIDC enablement (ADR-0195 next step). This interface + its adapter
 * are code-complete and unit-tested so that step is wiring only, no new Kotlin.
 */
@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ConsentValidateClient {

    @POST
    @Path("/{id}/validate")
    fun validate(@PathParam("id") id: UUID, request: ValidateConsentRequest): ConsentValidationResponse
}

/** Mirrors consent-service's own request DTO; kept local (not a cross-service import). */
data class ValidateConsentRequest(val granteeId: String, val requiredScope: String, val accountIban: String?)

/** Mirrors consent-service's own `ConsentValidationResponse` (ADR-0126 D2 projection). No PII. */
data class ConsentValidationResponse(
    val valid: Boolean,
    val reason: String?,
    val code: String?,
    val scopes: Set<String>? = null,
    /** Accounts the consent covers; null (when valid) = all of the party's accounts. */
    val grantedAccounts: List<String>? = null,
    val frequencyPerDay: Int? = null,
)

/** The MCP capability's required consent scope (consent-service `ConsentScope` enum, string form). */
object ConsentScopes {
    const val ACCOUNTS_READ = "ACCOUNTS_READ"
    const val BALANCES_READ = "BALANCES_READ"
    const val TRANSACTIONS_READ = "TRANSACTIONS_READ"
    const val STATEMENTS_READ = "STATEMENTS_READ"
    const val PAYMENTS_INITIATE = "PAYMENTS_INITIATE"
    const val PAYMENTS_STATUS_READ = "PAYMENTS_STATUS_READ"
}
