// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST client for `openbank-clearing-simulator` (ADR-0104 D2/D4): the licence swap-point.
 * Submits a `pacs.008` credit transfer and gets back a `pacs.002` status report in return.
 * OIDC service-to-service auth is propagated by [OidcClientRequestReactiveFilter].
 */
@RegisterRestClient(configKey = "clearing-simulator")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
interface ClearingSimulatorClient {
    @POST
    @Path("/api/v1/clearing/credit-transfers")
    @Consumes("application/xml")
    @Produces("application/xml")
    fun submitCreditTransfer(pacs008Xml: String): Uni<String>
}
