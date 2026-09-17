// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class KybAttestationStatusResponse(
    val id: UUID,
    val current: Boolean,
    val ruleTextHash: String,
    val confirmedSigners: Int,
    val confirmedRoles: List<String>,
)

/** KYB re-reads the live register; Party's historical signed-case projection cannot answer this. */
@Path("/api/v1/kyb/representation/attestations")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "kyb-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface KybAttestationRestClient {
    @GET
    @Path("/{id}/current")
    suspend fun current(@PathParam("id") id: UUID): KybAttestationStatusResponse
}
