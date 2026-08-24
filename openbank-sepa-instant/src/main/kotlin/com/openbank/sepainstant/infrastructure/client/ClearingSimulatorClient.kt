// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

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
 * REST client for the scheme gateway (ADR-0104 D4). Sends a real ISO 20022 `pacs.008` (XML) and
 * receives a `pacs.002` status report (XML). Bound to the `clearing-simulator` config key today;
 * the same interface points at a real gateway once one exists. Service-to-service auth via the
 * shared OIDC client filter (Bearer token).
 */
@RegisterRestClient(configKey = "clearing-simulator")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/clearing")
interface ClearingSimulatorClient {
    @POST
    @Path("/credit-transfers")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_XML)
    fun submitCreditTransfer(pacs008Xml: String): Uni<String>
}
