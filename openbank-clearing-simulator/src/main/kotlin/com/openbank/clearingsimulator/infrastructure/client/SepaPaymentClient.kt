// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST client for `openbank-sepa-payment-service`'s return ingestion endpoint.
 * Submits a `pacs.004` payment return XML and receives the service response.
 * OIDC service-to-service auth is propagated by [OidcClientRequestReactiveFilter].
 */
@RegisterRestClient(configKey = "sepa-payment-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
interface SepaPaymentClient {
    @POST
    @Path("/api/v1/sepa-payments/returns")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_JSON)
    fun handleReturn(pacs004Xml: String): Uni<Response>
}
