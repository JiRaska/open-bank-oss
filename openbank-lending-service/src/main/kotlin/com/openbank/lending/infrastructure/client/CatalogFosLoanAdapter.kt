// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.CatalogFosLoanPort
import com.openbank.lending.application.port.out.FosCommitmentRefused
import com.openbank.lending.application.port.out.FosOperation
import com.openbank.lending.application.port.out.FosRoute
import com.openbank.lending.application.port.out.FosRoutePin
import com.openbank.lending.domain.model.CatalogLoanSnapshot
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@RegisterRestClient(configKey = "product-catalog")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v2/internal/passport-commitments")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
interface CatalogFosLoanClient {
    @POST
    @Path("/route")
    fun route(request: Map<String, String>): Uni<FosRoute>

    @POST
    fun admit(request: Map<String, String>): Uni<Void>
}

@ApplicationScoped
class CatalogFosLoanAdapter(@RestClient private val client: CatalogFosLoanClient) : CatalogFosLoanPort {
    override fun resolve(grantId: UUID, partyId: UUID, revision: CatalogLoanSnapshot): Uni<FosRoute> =
        client.route(subject(grantId, partyId, revision)).onFailure(::conflict).transform { FosCommitmentRefused() }

    override fun admit(
        operation: FosOperation,
        pin: FosRoutePin,
        partyId: UUID,
        revision: CatalogLoanSnapshot,
    ): Uni<Unit> = client.admit(
        subject(pin.grantId, partyId, revision) + mapOf(
            "operation" to operation.action.name,
            "operationId" to operation.id.toString(),
            "binding" to operation.binding(partyId, revision),
            "hostCountry" to pin.route.hostCountry,
            "bankId" to pin.route.bankId.toString(),
            "establishmentId" to pin.route.establishmentId.toString(),
            "channel" to pin.route.channel,
            "customerSegment" to pin.route.customerSegment,
            "packSetDigest" to pin.route.packSetDigest,
        ),
    ).onFailure(::terminalRefusal).transform { FosCommitmentRefused() }.replaceWith(Unit)

    private fun subject(grantId: UUID, partyId: UUID, revision: CatalogLoanSnapshot) = mapOf(
        "grantId" to grantId.toString(),
        "partyId" to partyId.toString(),
        "revisionId" to revision.revisionId.toString(),
        "revisionContentHash" to revision.contentHash,
    )

    private fun conflict(error: Throwable): Boolean =
        error is WebApplicationException && error.response.status == HTTP_CONFLICT

    private fun terminalRefusal(error: Throwable): Boolean {
        val response = (error as? WebApplicationException)?.response ?: return false
        return response.status == HTTP_CONFLICT && response.getHeaderString("X-FOS-Decision") == "refused"
    }

    private companion object {
        const val HTTP_CONFLICT = 409
    }
}
