// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient

data class TppAuthorizationResponse(
    val tppId: String,
    val authorized: Boolean,
    val roles: Set<String>,
    val reason: String?,
)

@Path("/api/v1/tpp-registry")
@RegisterRestClient(configKey = "tpp-registry")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface TppRegistryRestClient {
    @GET
    @Path("/check")
    fun checkAuthorization(
        @QueryParam("tppId") tppId: String,
        @QueryParam("role") role: String,
    ): TppAuthorizationResponse
}

@ApplicationScoped
class TppAuthorizationGuard @Inject constructor(@RestClient private val client: TppRegistryRestClient) {

    @Timeout(2000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    fun requireAuthorized(tppId: String, role: String): TppAuthorizationResponse =
        client.checkAuthorization(tppId, role)
}
