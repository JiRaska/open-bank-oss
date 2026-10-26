// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.infrastructure.rest

import com.openbank.clearingsimulator.application.ClearingSimulatorService
import com.openbank.clearingsimulator.application.dto.ReturnRequest
import com.openbank.clearingsimulator.domain.ClearingRoutes
import com.openbank.clearingsimulator.infrastructure.client.SepaPaymentClient
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * The scheme/clearing simulator's inbound surface (ADR-0104 D2). The payment rails submit a real
 * `pacs.008` here exactly as they would to a CSM; the simulator answers with a real `pacs.002`
 * status report and, on settlement, a `camt.054` credit notification. NON-PRODUCTION counterparty:
 * no money moves, no real network is contacted. Base path `/api/v1` per ADR-0048.
 */
@Path("/api/v1/clearing")
@Tag(name = "Clearing simulator")
class ClearingResource {

    @Inject
    lateinit var simulator: ClearingSimulatorService

    @Inject
    @RestClient
    lateinit var sepaPaymentClient: SepaPaymentClient

    /**
     * Submit a `pacs.008` credit transfer; receive the `pacs.002` status report. A well-formed
     * transfer settles (`ACSC`) unless its amount triggers a deterministic reject; a message that
     * fails XSD validation is rejected with `RJCT`/`FF01`. Always HTTP 200 — the verdict is carried
     * in the status report, as a clearing system conveys it.
     */
    @POST
    @Path("/credit-transfers")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_XML)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Operation(summary = "Submit a pacs.008; receive a pacs.002 status report")
    fun submitCreditTransfer(pacs008Xml: String): Response =
        Response.ok(simulator.clear(pacs008Xml).statusReportXml).build()

    /**
     * Submit a `pacs.009` (bank-own FI credit transfer, CORE) on one of the three routes —
     * `certis` (CZK RTGS), `target2` (EUR RTGS) or `swift` (correspondent, any currency) — and
     * receive the `pacs.002`. Same verdict rules as `/credit-transfers`, plus `RJCT`/`AM03` when
     * the currency or clearing system does not belong to the route. An unknown route is 404.
     */
    @POST
    @Path("/fi-credit-transfers/{route}")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_XML)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Operation(summary = "Submit a pacs.009 on a clearing route; receive a pacs.002 status report")
    fun submitInstitutionCreditTransfer(@PathParam("route") route: String, pacs009Xml: String): Response {
        val clearingRoute = ClearingRoutes.fromPath(route) ?: throw NotFoundException("unknown clearing route")
        return Response.ok(simulator.clearInstitutionTransfer(clearingRoute, pacs009Xml).statusReportXml).build()
    }

    /**
     * The `pacs.002` the scheme holds for a bank-own transfer's UETR — read-only. 404 when it has
     * none, with the typed body `{"code":"UNKNOWN_UETR","uetr":...}`: the transfer never arrived. This is how the rail resolves a submission whose answer it
     * lost, instead of resending it.
     */
    @GET
    @Path("/fi-credit-transfers/status/{uetr}")
    // No @Produces: each branch sets its own type (XML pacs.002, or the JSON typed not-found);
    // declaring one here lets content negotiation overwrite the other branch's type.
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Operation(summary = "Status of a pacs.009 by UETR; 404 when the scheme has no record")
    fun institutionTransferStatus(@PathParam("uetr") uetr: String): Response {
        // A TYPED not-found: the rail resends only on this exact answer, never on a bare 404
        // (which a proxy, a wrong path or a missing route would also produce).
        val result = simulator.institutionTransfers.statusOf(uetr, java.time.Instant.now(simulator.clock))
            ?: return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(mapOf("code" to UNKNOWN_UETR, "uetr" to uetr))
                .build()
        return Response.ok(result.statusReportXml, MediaType.APPLICATION_XML).build()
    }

    /**
     * Simulate a `pacs.004` payment return (R-transaction). Generates a pacs.004 XML from the
     * supplied return details and forwards it to the sepa-payment service's return handler.
     * For integration testing the full R-transaction path without a live scheme.
     */
    @POST
    @Path("/returns")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Operation(summary = "Simulate a pacs.004 payment return — calls sepa-payment return handler")
    fun simulateReturn(request: ReturnRequest): Response {
        val pacs004Xml = simulator.generateReturn(request)
        val resp = sepaPaymentClient.handleReturn(pacs004Xml).await().indefinitely()
        return Response.status(resp.status)
            .entity(mapOf("pacs004Generated" to true, "upstreamStatus" to resp.status))
            .build()
    }

    /**
     * For a transfer that settles, the `camt.054` credit notification the simulator would push to
     * the beneficiary. Returns 204 when the transfer would be rejected (no notification is emitted).
     */
    @POST
    @Path("/notifications")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_XML)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Operation(summary = "Submit a pacs.008; receive the camt.054 credit notification if it settles")
    fun creditNotification(pacs008Xml: String): Response {
        val result = simulator.clear(pacs008Xml)
        return result.creditNotificationXml
            ?.let { Response.ok(it).build() }
            ?: Response.noContent().build()
    }

    private companion object {
        const val UNKNOWN_UETR = "UNKNOWN_UETR"
    }
}
