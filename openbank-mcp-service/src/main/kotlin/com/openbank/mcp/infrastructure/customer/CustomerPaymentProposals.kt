// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * delegation-service `POST /api/v1/payment-proposals` (ADR-0383 D6), the proposal store. Called on
 * mcp-service's OWN service-account (named oidc-client `m2m`, Keycloak client `openbank-mcp`), which
 * is the only identity the store admits to this route — and the only route of the store that
 * identity may call: it can create a PENDING proposal and nothing else (no read-back, no approve,
 * no execution). This interface deliberately declares no other method.
 */
@RegisterRestClient(configKey = "delegation-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/payment-proposals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface PaymentProposalStoreClient {
    @POST
    fun create(
        @HeaderParam(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        request: CreatePaymentProposalRequest,
    ): McpProposalReceipt

    companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
    }
}

/**
 * Mirrors delegation-service's `CreatePaymentProposalRequest`; kept local (no cross-service import).
 * [amount] is a STRING on the wire: it never passes through a double.
 */
data class CreatePaymentProposalRequest(
    val partyId: UUID,
    val mcpClientId: String,
    val keyThumbprint: String,
    val operationType: String,
    val debtorAccountId: UUID,
    val creditorIban: String,
    val creditorName: String,
    val amount: String,
    val currency: String,
    val reference: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val connectionId: UUID? = null,
)

/** The store's answer to mcp-service: an id, a state and an expiry — never an approval handle. */
data class McpProposalReceipt(
    val proposalId: UUID? = null,
    val status: String? = null,
    val deduplicated: Boolean? = null,
    val expiresAt: String? = null,
    val summary: String? = null,
)

/** The store, as the propose tool sees it. Every failure is a [ProposalStoreRefusal] or a transport error. */
interface PaymentProposalStore {
    fun create(idempotencyKey: String, request: CreatePaymentProposalRequest): McpProposalReceipt
}

/** A 4xx from the store, with its machine-readable `code` when it sent one. */
class ProposalStoreRefusal(val status: Int, val code: String?) :
    RuntimeException("proposal store refused ($status${code?.let { " $it" } ?: ""})")

@ApplicationScoped
class RestPaymentProposalStore @Inject constructor(
    @RestClient private val client: PaymentProposalStoreClient,
    private val mapper: ObjectMapper,
) : PaymentProposalStore {
    override fun create(idempotencyKey: String, request: CreatePaymentProposalRequest): McpProposalReceipt = try {
        client.create(idempotencyKey, request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: WebApplicationException) {
        val status = e.response?.status ?: 0
        if (status in CLIENT_ERRORS) throw ProposalStoreRefusal(status, codeOf(e)) else throw e
    }

    @Suppress("TooGenericExceptionCaught") // an unreadable error body only loses the code, never the refusal
    private fun codeOf(e: WebApplicationException): String? = try {
        val body: JsonNode = mapper.readTree(e.response.readEntity(String::class.java))
        body.path("code").takeIf { it.isTextual }?.asText()?.takeIf { CODE_SHAPE.matches(it) }
    } catch (ignored: Exception) {
        null
    }

    private companion object {
        val CLIENT_ERRORS = 400..499
        val CODE_SHAPE = Regex("^[A-Z_]{1,64}$")
    }
}
