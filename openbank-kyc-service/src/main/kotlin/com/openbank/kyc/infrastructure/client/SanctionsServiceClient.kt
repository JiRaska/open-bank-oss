// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * RestClient binding to `openbank-sanctions-service` synchronous screen
 * (`POST /api/v1/sanctions/screen`). Mirrors the same-named client already used by
 * openbank-domestic-payment / openbank-sepa-payment / openbank-fx-service / openbank-account-service
 * (ADR-0032 §D) — carries the service OIDC token via the reactive client filter.
 *
 * kyc-service scopes every call to `listTypes = ["PEP_GLOBAL"]` (see [SanctionsScreeningAdapter]):
 * this is a dedicated PEP check, not the broader sanctions gate.
 */
@RegisterRestClient(configKey = "sanctions-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/sanctions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SanctionsServiceClient {

    @POST
    @Path("/screen")
    fun screen(request: ScreenRequest): Uni<ScreenResponse>
}

/** Mirror of sanctions-service `ScreenEntityCommand` (only the fields a PEP-only screen needs). */
data class ScreenRequest(
    val idempotencyKey: String,
    val entityType: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val listTypes: List<String>? = null,
)

/** Subset of the sanctions-service `SanctionsCheck` payload we act on. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenResponse(
    val status: String? = null,
    val overallScore: Double? = null,
    val matches: List<ScreenMatch> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenMatch(val matchedName: String? = null, val matchScore: Double? = null)
