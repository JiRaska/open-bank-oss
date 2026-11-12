// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.GsftAccountRosterService
import com.openbank.account.application.usecase.GsftRosterEntry
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.jboss.resteasy.reactive.RestResponse
import java.time.LocalDate

@Path("/api/v1/accounts/reporting/deposits")
@Produces(MediaType.APPLICATION_JSON)
class GsftAccountRosterResource(private val roster: GsftAccountRosterService, private val identity: SecurityIdentity) {
    @GET
    @RolesAllowed(Roles.API)
    @Authorize(action = "account.gsft.roster")
    @Operation(summary = "List deposit accounts as of a Czech reporting date for GSFT")
    suspend fun list(
        @QueryParam("asOf") asOf: String?,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): RestResponse<CursorPage<GsftRosterEntry>> {
        if (!ServiceAccountIdentity.isOneOf(identity, GSFT_CALLERS)) {
            throw ForbiddenException("caller is not the GSFT reporting service")
        }
        val day = requireNotNull(asOf?.let { runCatching { LocalDate.parse(it) }.getOrNull() }) {
            "asOf must be an ISO local date"
        }
        return RestResponse.ResponseBuilder.ok(roster.list(day, limit, cursor))
            .header("Cache-Control", "no-store")
            .build()
    }

    private companion object {
        val GSFT_CALLERS = setOf("service-account-openbank-tax-reporting")
    }
}
