// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest

import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/** A purpose-limited proof check. No metadata or document bytes leave this route. */
@Path("/api/v1/documents/lending-guarantee-evidence")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class LendingGuaranteeEvidenceResource(
    private val query: DocumentQueryUseCase,
    private val identity: SecurityIdentity,
    @ConfigProperty(name = "openbank.document.bank-scope") private val deploymentBankScope: String,
) {
    @POST
    @Path("/verify")
    @RolesAllowed("ROLE_LENDING_GRAPH_PROOF")
    @Authorize(action = "document.guaranteeEvidence.verify", resource = "")
    suspend fun verify(req: LendingGuaranteeEvidenceRequest?): LendingGuaranteeEvidenceResponse {
        // The existing openbank-services credential is shared across backends. Even if OPA is
        // advisory during rollout, it must not turn that credential into a document oracle.
        if (identity.principal?.name != LENDING_GRAPH_PRINCIPAL) throw ForbiddenException()
        val request = requireNotNull(req) { "request body is required" }
        require(request.bankScope.matches(BANK_SCOPE_PATTERN)) { "invalid bank scope" }
        if (request.bankScope != deploymentBankScope) throw ForbiddenException()
        require(request.sealedSha256.matches(SHA256_PATTERN)) { "invalid sealed SHA-256" }
        val document = query.getMetadata(request.documentId)
        return LendingGuaranteeEvidenceResponse(document?.matches(request) == true)
    }

    private companion object {
        const val LENDING_GRAPH_PRINCIPAL = "service-account-openbank-lending-graph"
        val BANK_SCOPE_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class LendingGuaranteeEvidenceRequest(
    val documentId: UUID,
    val loanId: UUID,
    val guarantorPartyId: UUID,
    val bankScope: String,
    val sealedSha256: String,
)

data class LendingGuaranteeEvidenceResponse(val matches: Boolean)

internal fun Document.matches(request: LendingGuaranteeEvidenceRequest): Boolean = id == request.documentId &&
    status == DocumentStatus.SIGNED &&
    sealedSha256 == request.sealedSha256 &&
    caseRef == request.loanId.toString() &&
    partyRef == request.guarantorPartyId.toString() &&
    bankScope == request.bankScope
