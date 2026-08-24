// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST client for the scheme gateway (ADR-0104 D3). Sends a real ISO 20022 `pacs.008` (XML) and
 * receives a `pacs.002` status report (XML). Bound to the `clearing-simulator` config key today;
 * the same interface points at a real gateway once one exists.
 *
 * Service-to-service auth: the `Authorization` Bearer is passed **explicitly** by [SchemeGatewayAdapter]
 * rather than via `OidcClientRequestReactiveFilter`. That filter does not attach a token when the
 * call originates from a Temporal-activity Vert.x duplicated context (the production path) — the
 * scheme submission then arrives unauthenticated and the simulator answers 401 (ADR-0104 BUG #3).
 */
@RegisterRestClient(configKey = "clearing-simulator")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/clearing")
interface ClearingSimulatorClient {
    @POST
    @Path("/credit-transfers")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_XML)
    fun submitCreditTransfer(
        @HeaderParam(HttpHeaders.AUTHORIZATION) authorization: String,
        pacs008Xml: String,
    ): Uni<String>
}
