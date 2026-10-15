// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.pension.application.onboarding.IntegrationUnavailableException
import com.openbank.pension.application.onboarding.OnboardingNotFoundException
import com.openbank.pension.application.onboarding.SignatureRejectedException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/** Only the exceptions slice S2 owns; `IllegalStateException` -> 409 is already S1's mapper. */
class OnboardingExceptionMappers {

    @ServerExceptionMapper
    fun notFound(e: OnboardingNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun unavailable(e: IntegrationUnavailableException): Response =
        Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun signatureRejected(e: SignatureRejectedException): Response =
        Response.status(UNPROCESSABLE).entity(mapOf("error" to e.message)).build()

    private companion object {
        const val UNPROCESSABLE = 422
    }
}
