// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.pid.application.port.`in`.EudiVerifyPresentationUseCase
import com.openbank.pid.application.port.`in`.VerifyPresentationCommand
import com.openbank.pid.infrastructure.rest.dto.VerifyMdocRequest
import com.openbank.pid.infrastructure.rest.dto.VerifyPresentationRequest
import com.openbank.pid.infrastructure.rest.dto.toVerifyResponse
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * EUDI wallet presentation endpoint (eIDAS 2.0, ADR-0094). pid acts as an OpenID4VP Relying Party:
 * it cryptographically verifies a PID (Person Identification Data) verifiable presentation and resolves
 * the verified identity against the dedup authority (tier-0). The full OpenID4VP authorization-request /
 * redirect / QR dance is out of scope here — this endpoint accepts a directly-submitted VP token over
 * the trusted M2M leg; the cryptographic verification is real (see EudiPresentationVerifierImpl).
 */
@Path("/api/v1/parties/eudi")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "EUDI identity", description = "eIDAS 2.0 wallet presentation verification (ADR-0094)")
class EudiPresentationResource(private val eudiVerify: EudiVerifyPresentationUseCase) {

    @POST
    @Path("/verify-presentation")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "identity.eudi.verify")
    @Operation(summary = "Verify an EUDI wallet PID presentation and resolve the identity (ADR-0094 tier-0)")
    suspend fun verifyPresentation(request: VerifyPresentationRequest): Response {
        val result = eudiVerify.verifyAndResolve(
            VerifyPresentationCommand(
                vpToken = request.vpToken,
                nonce = request.nonce,
                audience = request.audience,
            ),
        )
        return Response.ok(result.toVerifyResponse()).build()
    }

    @POST
    @Path("/verify-mdoc")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "identity.eudi.verify")
    @Operation(summary = "Verify an ISO 18013-5 mdoc PID (CBOR/COSE) and resolve the identity (ADR-0094 tier-0)")
    suspend fun verifyMdoc(request: VerifyMdocRequest): Response {
        val result = eudiVerify.verifyAndResolveMdoc(request.mdoc)
        return Response.ok(result.toVerifyResponse()).build()
    }
}
