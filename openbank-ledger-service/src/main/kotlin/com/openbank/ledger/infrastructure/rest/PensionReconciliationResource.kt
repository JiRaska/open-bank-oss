// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.infrastructure.persistence.repository.PensionBookReconciliation
import com.openbank.ledger.infrastructure.persistence.repository.PensionReconciliationProposal
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.util.UUID

@Path("/api/v1/ledger/pension-accounting/reconciliations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class PensionReconciliationResource(
    private val reconciliations: PensionBookReconciliation,
    private val identity: SecurityIdentity,
) {
    @POST
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.pension.reconcile", resource = "")
    suspend fun propose(request: PensionReconciliationProposal?): Map<String, UUID> {
        val actor = checkerSubject()
        requireNotNull(request) { "a request body is required" }
        return mapOf("id" to reconciliations.propose(request, actor))
    }

    @POST
    @Path("/{id}/approve")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.pension.reconcile", resource = "#id")
    suspend fun approve(@PathParam("id") id: UUID): Map<String, UUID> {
        val actor = checkerSubject()
        reconciliations.approve(id, actor)
        return mapOf("id" to id)
    }

    private companion object {
        const val MAX_SUBJECT_LENGTH = 190
    }

    private fun checkerSubject(): String {
        if (!com.openbank.libs.authz.ServiceAccountIdentity.isHumanStaff(identity)) {
            throw jakarta.ws.rs.ForbiddenException("A verified interactive accountant is required")
        }
        val subject = (identity.principal as org.eclipse.microprofile.jwt.JsonWebToken).subject
        require(subject.length <= MAX_SUBJECT_LENGTH) { "Accountant identity exceeds the supported length" }
        return "oidc-sub:$subject"
    }
}
