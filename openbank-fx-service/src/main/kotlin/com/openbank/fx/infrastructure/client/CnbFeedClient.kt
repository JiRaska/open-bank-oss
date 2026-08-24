// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.libs.web.SyntheticTaintExternalBoundary
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * RestClient binding for the ČNB central-bank exchange-rate fixing daily text feed. The base URL
 * and path are config-driven (`quarkus.rest-client.cnb-feed.url`); the feed accepts an optional
 * `date` query parameter in `DD.MM.YYYY` form and returns the fixing as `text/plain`.
 *
 * This is the bank's only ingress for the ČNB rate (FX is `openbank-fx-service`'s bounded context):
 * no OIDC filter is registered because the ČNB feed is a public, unauthenticated endpoint.
 */
@RegisterRestClient(configKey = "cnb-feed")
@SyntheticTaintExternalBoundary("public Czech National Bank exchange-rate feed is outside OpenBank")
@Produces(MediaType.TEXT_PLAIN)
interface CnbFeedClient {

    @GET
    fun daily(@QueryParam("date") date: String?): Uni<String>
}
