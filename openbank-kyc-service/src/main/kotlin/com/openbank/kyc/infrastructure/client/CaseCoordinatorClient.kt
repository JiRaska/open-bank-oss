// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

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
 * RestClient binding to case-coordinator-agent's case-open authority (ADR-0244 D9, ADR-0256
 * contract `POST /api/v1/case-coordinator/cases`). Carries the service OIDC token via the
 * reactive client filter, same as [SanctionsServiceClient].
 *
 * The raw [Response] is returned deliberately: the adapter classifies 201/409/429/503/4xx into
 * [com.openbank.kyc.application.port.out.CaseOpenOutcome] — rest-client exception mappers would
 * collapse the distinction the ADR-0256 contract makes between idempotent duplicates (409),
 * retryable overload (429/503) and deterministic rejection (4xx).
 */
@RegisterRestClient(configKey = "case-coordinator")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/case-coordinator")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface CaseCoordinatorClient {

    @POST
    @Path("/cases")
    fun openCase(request: OpenCaseRequest): Uni<Response>
}

/**
 * The coordinator OpenAPI contract verbatim (`AML_ALERT` is the wire enum spelling; governance
 * uses the `aml-alert` class key). [openedBy] names WHICH agent identity the call acts as — the
 * coordinator authorizes it against the caller's proved roles, never trusting the claim.
 */
data class OpenCaseRequest(
    val caseClass: String,
    val subjectRef: String,
    val openedBy: String,
    val dispositionTarget: String,
)
