// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.client

import com.openbank.pensionfund.infrastructure.accounting.FeeDeliveryReceipt
import com.openbank.pensionfund.infrastructure.accounting.FeePosting
import com.openbank.pensionfund.infrastructure.accounting.PensionBookDescriptor
import com.openbank.pensionfund.infrastructure.accounting.PensionCloseReport
import com.openbank.pensionfund.infrastructure.accounting.PensionClosingDay
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import java.time.LocalDate

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
interface PensionBookClient : AutoCloseable {
    @POST
    @Path("/api/v1/ledger/pension-accounting/management-fees")
    suspend fun post(@HeaderParam("x-openbank-synthetic") synthetic: String?, request: FeePosting): FeeDeliveryReceipt

    @GET
    @Path("/api/v1/ledger/pension-accounting/identity")
    suspend fun identity(): PensionBookDescriptor

    @GET
    @Path("/api/v1/ledger/pension-accounting/closing-days")
    suspend fun pendingDays(): List<PensionClosingDay>

    @GET
    @Path("/api/v1/ledger/pension-accounting/closing-days")
    suspend fun days(@QueryParam("from") from: LocalDate, @QueryParam("to") to: LocalDate): List<PensionClosingDay>

    @PUT
    @Path("/api/v1/ledger/accounting-days/{date}/participants/pension-fund")
    suspend fun report(@PathParam("date") date: LocalDate, report: PensionCloseReport)
}
