// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.rest

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.interest.application.port.`in`.GetAccrualsUseCase
import com.openbank.interest.application.port.`in`.ManageRateConfigUseCase
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.AccrualSummary
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.model.RateConfigNotFoundException
import com.openbank.interest.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Path("/api/v1/interest")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Interest", description = "Interest accrual and capitalization")
@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
class InterestResource(
    private val accrueUseCase: AccrueInterestUseCase,
    private val capitalizeUseCase: CapitalizeInterestUseCase,
    private val getAccrualsUseCase: GetAccrualsUseCase,
    private val rateConfigUseCase: ManageRateConfigUseCase,
    private val clock: Clock,
) {
    @GET
    @Path("/accruals")
    @Operation(summary = "List all interest accruals")
    @Authorize(action = "interest.list", resource = "")
    fun listAllAccruals() = getAccrualsUseCase.listAllAccruals().flatMap { accruals ->
        rateConfigUseCase.listConfigs(null).map { configs ->
            val configsById = configs.associateBy(InterestRateConfig::id)
            accruals.map { accrual ->
                accrual.toResponse(configsById[accrual.configId]?.dayCount ?: DayCount.ACT_365)
            }
        }
    }

    @POST
    @Path("/accrue")
    @Operation(summary = "Accrue interest for an account")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "interest.create", resource = "")
    fun accrue(request: AccrualRequest?): Uni<Response> {
        // A JSON `null` body deserialises to null despite the non-nullable Kotlin type, so the
        // first field access threw NPE and this answered 500 (#3038). libs-runtime maps
        // IllegalArgumentException to 400.
        requireNotNull(request) { "request body is required" }
        return accrueUseCase.accrue(request)
            .map { Response.status(201).entity(it).build() }
            .onFailure(RateConfigNotFoundException::class.java).recoverWithItem { e ->
                // No rate for this (account/product, currency): a client/config condition, not a 500.
                Response.status(422).entity(mapOf("error" to e.message)).build()
            }
        // The untyped `.onFailure().recoverWithItem { serverError }` that used to sit here is gone
        // (#3057): it stamped 500 over failures libs-runtime already maps correctly — an
        // IllegalArgumentException that should be 400, and now DateTimeException too.
    }

    @POST
    @Path("/accrue/all")
    @Operation(summary = "Trigger daily accrual for all accounts")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "interest.trigger", resource = "")
    fun accrueAll(@QueryParam("date") date: String?): Uni<Response> =
        accrueUseCase.accrueAll(date?.let { LocalDate.parse(it) } ?: LocalDate.now(clock))
            .map { Response.ok(mapOf("processed" to it)).build() }

    @POST
    @Path("/capitalize/{accountId}")
    @Operation(summary = "Capitalize accrued interest for an account")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "interest.create", resource = "#accountId")
    fun capitalize(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("productId") productId: String,
        @QueryParam("toDate") toDate: String?,
    ): Uni<Response> =
        capitalizeUseCase.capitalize(accountId, productId, toDate?.let { LocalDate.parse(it) } ?: LocalDate.now(clock))
            .map { Response.ok(it).build() }

    @GET
    @Path("/accruals/{accountId}")
    @Operation(summary = "Get interest accruals for an account")
    @Authorize(action = "interest.read", resource = "#accountId")
    fun getAccruals(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
    ): Uni<List<InterestAccrual>> =
        getAccrualsUseCase.getAccruals(accountId, from?.let { LocalDate.parse(it) }, to?.let { LocalDate.parse(it) })

    @GET
    @Path("/accruals/{accountId}/summary")
    @Operation(summary = "Get interest accrual summary for an account")
    @Authorize(action = "interest.read", resource = "#accountId")
    fun getSummary(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("from") @DefaultValue("") from: String,
        @QueryParam("to") @DefaultValue("") to: String,
    ): Uni<AccrualSummary> {
        val fromDate = if (from.isNotEmpty()) LocalDate.parse(from) else LocalDate.now(clock).minusMonths(1)
        val toDate = if (to.isNotEmpty()) LocalDate.parse(to) else LocalDate.now(clock)
        return getAccrualsUseCase.getSummary(accountId, fromDate, toDate)
    }

    @GET
    @Path("/capitalizations/{accountId}")
    @Operation(summary = "Get capitalizations for an account")
    @Authorize(action = "interest.read", resource = "#accountId")
    fun getCapitalizations(@PathParam("accountId") accountId: UUID): Uni<List<InterestCapitalization>> =
        getAccrualsUseCase.getCapitalizations(accountId)

    @POST
    @Path("/rates")
    @Operation(summary = "Create interest rate configuration")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "interest.create", resource = "")
    fun createRateConfig(config: InterestRateConfig?): Uni<Response> {
        // A JSON `null` body deserialises to null despite the non-nullable Kotlin type, so the
        // first field access threw NPE and this answered 500 (#3038). libs-runtime maps
        // IllegalArgumentException to 400.
        requireNotNull(config) { "request body is required" }
        return rateConfigUseCase.createConfig(config)
            .map { Response.status(201).entity(it).build() }
    }

    @GET
    @Path("/rates")
    @Operation(summary = "List interest rate configurations")
    @Authorize(action = "interest.list", resource = "")
    fun listRateConfigs(@QueryParam("productId") productId: String?): Uni<List<InterestRateConfig>> =
        rateConfigUseCase.listConfigs(productId)

    @GET
    @Path("/accounts/{accountId}/effective-rate")
    @Operation(summary = "The interest rate effective for an account (override, else product default)")
    @Authorize(action = "interest.read", resource = "#accountId")
    fun effectiveRate(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("productId") productId: String,
        @QueryParam("date") @DefaultValue("") date: String,
    ): Uni<Response> {
        val on = if (date.isNotEmpty()) LocalDate.parse(date) else LocalDate.now(clock)
        return rateConfigUseCase.effectiveRate(accountId, productId, on)
            .map { it?.let { c -> Response.ok(c).build() } ?: Response.noContent().build() }
    }

    @GET
    @Path("/rates/{id}")
    @Operation(summary = "Get interest rate configuration by ID")
    @Authorize(action = "interest.read", resource = "#id")
    fun getRateConfig(@PathParam("id") id: UUID): Uni<Response> = rateConfigUseCase.getConfig(id)
        .map { it?.let { c -> Response.ok(c).build() } ?: Response.status(404).build() }

    @DELETE
    @Path("/rates/{id}")
    @Operation(summary = "Deactivate interest rate configuration")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "interest.delete", resource = "#id")
    fun deactivateRateConfig(@PathParam("id") id: UUID): Uni<Response> = rateConfigUseCase.deactivateConfig(id)
        .map { Response.ok(it).build() }
}
