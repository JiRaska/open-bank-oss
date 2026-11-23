// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.time.OffsetDateTime
import java.util.UUID

@RegisterRestClient(configKey = "mcp-connect-sca-receipt")
@OidcClientFilter("m2m")
@Path("/api/v1/sca/challenges")
interface McpConnectScaReceiptClient {
    @GET
    @Path("/{id}/mcp-connect-receipt")
    suspend fun read(@PathParam("id") id: UUID): Receipt

    data class Receipt(
        val challengeId: UUID,
        val partyId: UUID,
        val decidedByPartyId: UUID,
        val purpose: String,
        val approvalRequestId: String,
        val contextSha256: String,
        val consumedAt: OffsetDateTime,
    )
}
