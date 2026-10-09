// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest.funding

import com.openbank.pension.application.usecase.ClaimBatchNotFoundException
import com.openbank.pension.application.usecase.IncentiveClaimNotFoundException
import com.openbank.pension.application.usecase.UnmatchedPaymentNotFoundException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/** 404s for the S3 not-found types; everything else maps through S1's mappers and libs-runtime. */
class FundingExceptionMappers {

    @ServerExceptionMapper(
        UnmatchedPaymentNotFoundException::class,
        ClaimBatchNotFoundException::class,
        IncentiveClaimNotFoundException::class,
    )
    fun notFound(e: RuntimeException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()
}
