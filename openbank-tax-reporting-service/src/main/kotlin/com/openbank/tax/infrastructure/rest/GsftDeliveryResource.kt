// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.tax.infrastructure.returns.gsft.GsftDelivery
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.Base64
import java.util.UUID

/**
 * GSFT depositor-data delivery (issue #15356). Assembly, four-eyes approval and the regulator's
 * acknowledgement are the generic statutory-return routes (catalogue `cz-gsft`); only submission is
 * GSFT-specific, because it must re-verify the attested package and hand over its PGP encryption.
 * Same guards as the generic lifecycle: operator role, an `@Authorize` action granted to staff
 * only (`tax_reporting_rest_ext.rego`), and [TaxReportingActor] refusing a service-account.
 */
@Path("/api/v1/statutory-returns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "StatutoryReturn", description = "Catalogue-defined regulatory returns (ADR-0336)")
class GsftDeliveryResource(private val delivery: GsftDelivery) {
    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Path("/{id}/gsft-delivery")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "tax.statutory-return.deliver", resource = "#id")
    @Operation(
        summary = "Submit an approved GSFT batch: verify the attested package, PGP-encrypt it (APPROVED → SUBMITTED)",
    )
    suspend fun deliver(@PathParam("id") id: String, request: GsftDeliveryRequest?): Response {
        val reference = requireNotNull(request?.reference) { "reference is required" }
        val returnId = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw WebApplicationException("id must be a UUID (got '$id')", Response.Status.BAD_REQUEST)
        val actor = TaxReportingActor.staffSubject(identity, "submit a GSFT depositor batch")
        val result = delivery.deliver(returnId, reference, actor)
        return Response.ok(
            GsftDeliveryResponse(
                statutoryReturn = result.statutoryReturn.toResponse(),
                fileName = result.fileName,
                encryptedPackage = Base64.getEncoder().encodeToString(result.encrypted),
            ),
        ).build()
    }
}

data class GsftDeliveryRequest(val reference: String? = null)

data class GsftDeliveryResponse(
    val statutoryReturn: StatutoryReturnResponse,
    val fileName: String,
    /** Base64 of the binary OpenPGP message over the ZIP archive. */
    val encryptedPackage: String,
)
