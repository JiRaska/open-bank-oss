// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.usecase.AccountingClassService
import com.openbank.ledger.domain.model.AccountingClassMapping
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Read side of the ledger-owned accountingClass -> GL account map (ADR-0341 D3). Posters do not
 * need it (they send the class on the journal line and the ledger resolves it), but regulatory
 * readers and reconciliations do: it answers "which account does this product's money land in"
 * from the same table the posting path uses. There is no write endpoint on purpose: the map
 * changes by reviewed migration, exactly as the chart does.
 */
@Path("/api/v1/accounting-classes")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting classes", description = "accountingClass to GL account map (ADR-0341)")
class AccountingClassResource(private val classes: AccountingClassService) {

    @GET
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "")
    @Operation(summary = "List every accountingClass to GL account mapping")
    suspend fun list(): Response = Response.ok(classes.list().map { it.toResponse() }).build()

    @GET
    @Path("/resolve")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "")
    @Operation(summary = "Resolve one (accountingClass, role, currency) to its GL account")
    suspend fun resolve(
        // Nullable on purpose: an absent query parameter reaches a non-null Kotlin parameter as null
        // and would answer 500 instead of 400.
        @QueryParam("accountingClass") accountingClass: String?,
        @QueryParam("role") role: String?,
        @QueryParam("currency") currency: String?,
    ): Response {
        requireNotNull(accountingClass) { "query parameter 'accountingClass' is required" }
        requireNotNull(role) { "query parameter 'role' is required" }
        requireNotNull(currency) { "query parameter 'currency' is required" }
        return Response.ok(classes.resolve(accountingClass, role, currency).toResponse()).build()
    }
}

data class AccountingClassMappingResponse(
    val accountingClass: String,
    val role: String,
    val currencyCode: String,
    val glAccountId: UUID,
    val glCode: String,
)

private fun AccountingClassMapping.toResponse() = AccountingClassMappingResponse(
    accountingClass = accountingClass,
    role = role.name,
    currencyCode = currencyCode,
    glAccountId = glAccountId,
    glCode = glCode,
)
