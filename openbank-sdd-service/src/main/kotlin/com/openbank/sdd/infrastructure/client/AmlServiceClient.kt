// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/** RestClient binding to aml-service's case store, as service-account-openbank-sdd (amlCase.create). */
@RegisterRestClient(configKey = "aml-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/aml/cases")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AmlServiceClient {

    @POST
    fun createCase(@HeaderParam("Idempotency-Key") idempotencyKey: String, request: CreateAmlCaseRequest): Uni<Response>
}

/** Mirror of aml-service `CreateAmlCaseRequest`. */
data class CreateAmlCaseRequest(
    val partyId: UUID,
    val accountId: UUID?,
    val transactionId: UUID?,
    val customerReference: String,
    val screeningType: String,
    val riskLevel: String,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
)
