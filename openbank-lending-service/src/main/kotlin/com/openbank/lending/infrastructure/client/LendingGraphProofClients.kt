// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.LendingGraphProofPort
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/** The named client is mandatory: the default `openbank-services` token cannot read these proofs. */
@RegisterRestClient(configKey = "lending-graph-party-proof")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("lending-graph")
@Path("/api/v1/parties/lending-guarantor-identity")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
interface LendingGraphPartyProofClient {
    @POST
    @Path("/verify")
    fun verify(request: GuarantorIdentityProofRequest): Uni<GuarantorIdentityProofResponse>
}

data class GuarantorIdentityProofRequest(val partyId: UUID)

data class GuarantorIdentityProofResponse(val verified: Boolean)

@RegisterRestClient(configKey = "lending-graph-document-proof")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("lending-graph")
@Path("/api/v1/documents/lending-guarantee-evidence")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
interface LendingGraphDocumentProofClient {
    @POST
    @Path("/verify")
    fun verify(request: SignedGuaranteeProofRequest): Uni<SignedGuaranteeProofResponse>
}

data class SignedGuaranteeProofRequest(
    val documentId: UUID,
    val loanId: UUID,
    val guarantorPartyId: UUID,
    val bankScope: String,
    val sealedSha256: String,
)

data class SignedGuaranteeProofResponse(val matches: Boolean)

@ApplicationScoped
class RestLendingGraphProofAdapter(
    @param:RestClient private val party: LendingGraphPartyProofClient,
    @param:RestClient private val document: LendingGraphDocumentProofClient,
) : LendingGraphProofPort {
    override suspend fun hasVerifiedGuarantorIdentity(partyId: UUID): Boolean =
        party.verify(GuarantorIdentityProofRequest(partyId)).awaitSuspending().verified

    override suspend fun matchesSignedGuarantee(
        documentId: UUID,
        loanId: UUID,
        guarantorPartyId: UUID,
        bankScope: String,
        sealedSha256: String,
    ): Boolean = document.verify(
        SignedGuaranteeProofRequest(documentId, loanId, guarantorPartyId, bankScope, sealedSha256),
    ).awaitSuspending().matches
}
