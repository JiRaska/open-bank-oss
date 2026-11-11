// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

@OidcClientFilter("m2m")
@RegisterRestClient(configKey = "kyb-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/kyb/cases")
interface KybStatutoryRuleRestClient {
    @GET
    @Path("/{id}/statutory-representation/{attestationId}/current")
    suspend fun verify(
        @PathParam("id") caseId: UUID,
        @PathParam("attestationId") attestationId: UUID,
        @QueryParam("entityPartyId") entityPartyId: UUID,
    ): Response
}
