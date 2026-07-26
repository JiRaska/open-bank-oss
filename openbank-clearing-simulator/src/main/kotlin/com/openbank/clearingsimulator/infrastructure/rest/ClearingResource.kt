// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.infrastructure.rest

import com.openbank.clearingsimulator.application.ClearingSimulatorService
import com.openbank.clearingsimulator.application.dto.ReturnRequest
import com.openbank.clearingsimulator.infrastructure.client.SepaPaymentClient
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
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
}
