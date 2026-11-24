// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.annotation.JsonInclude
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * delegation-service `POST /api/v1/delegations/mcp-client/resolve` (ADR-0383 D3), authorised as
 * `delegation.mcp-client.resolve` for mcp-service's OWN service-account only (named oidc-client
 * `m2m`, Keycloak client `openbank-mcp`). This is a control-plane read of the customer's grant —
 * never a data read: no account data is fetched on this identity.
 */
@RegisterRestClient(configKey = "delegation-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/delegations/mcp-client")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface DelegationGrantClient {
    @POST
    @Path("/resolve")
    fun resolve(request: ResolveMcpClientGrantRequest): ResolveMcpClientGrantResponse
}

/** Mirrors delegation-service's request DTO; kept local (no cross-service import). */
data class ResolveMcpClientGrantRequest(
    val grantorPartyId: UUID,
    val clientId: String,
    val keyThumbprint: String?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val connectionId: UUID? = null,
)

data class ResolveMcpClientGrantResponse(val grants: List<McpClientGrantDto>? = null)

data class McpClientGrantDto(
    val grantId: UUID? = null,
    val resourceType: String? = null,
    val resourceId: UUID? = null,
    val capabilities: Set<String>? = null,
    val validTo: OffsetDateTime? = null,
)

/**
 * The delegation-service-backed [CustomerGrantSource]. Fails CLOSED: a transport error, an error
 * status or a malformed body is [GrantLookupUnavailable], never an empty (or partial) grant list.
 * Only ACCOUNT grants are kept — the customer tools are account reads.
 */
@ApplicationScoped
class DelegationGrantSource @Inject constructor(@RestClient private val client: DelegationGrantClient) :
    CustomerGrantSource {
    @Suppress("TooGenericExceptionCaught") // every failure mode must become the one fail-closed signal
    override fun resolve(key: GrantKey): List<CustomerGrant> {
        val response = try {
            client.resolve(ResolveMcpClientGrantRequest(key.partyId, key.clientId, key.keyThumbprint, key.connectionId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GrantLookupUnavailable("delegation grant lookup failed: ${e.javaClass.simpleName}", e)
        }
        val grants = response.grants ?: throw GrantLookupUnavailable("delegation grant lookup returned no grant list")
        return grants.filter { it.resourceType == ACCOUNT }.map {
            CustomerGrant(
                grantId = it.grantId ?: throw GrantLookupUnavailable("grant without an id"),
                accountId = it.resourceId ?: throw GrantLookupUnavailable("grant without a resource"),
                capabilities = it.capabilities.orEmpty(),
                validTo = it.validTo?.toInstant(),
            )
        }
    }

    private companion object {
        const val ACCOUNT = "ACCOUNT"
    }
}
