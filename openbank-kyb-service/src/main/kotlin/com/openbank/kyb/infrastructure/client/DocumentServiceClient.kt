// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.kyb.application.port.out.AgreementDisclosure
import com.openbank.kyb.application.port.out.BusinessAgreementRequest
import com.openbank.kyb.application.port.out.BusinessAgreementView
import com.openbank.kyb.application.port.out.CeremonySigner
import com.openbank.kyb.application.port.out.CeremonySignerStatus
import com.openbank.kyb.application.port.out.CeremonyStatus
import com.openbank.kyb.application.port.out.DocumentGateway
import com.openbank.kyb.domain.model.AgreementConflictException
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class CeremonySignerBody(val partyRef: UUID, val status: String, val signedAt: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DisclosureBody(
    val code: String,
    val version: String,
    val title: String? = null,
    val sha256: String,
    val documentId: UUID? = null,
)

/** document-service `BusinessAgreement` (shared business-contract spec, W1). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BusinessAgreementBody(
    val caseId: UUID,
    val documentId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val sealedSha256: String? = null,
    val ceremonyId: UUID,
    val ceremonyStatus: String,
    val signers: List<CeremonySignerBody> = emptyList(),
    val disclosures: List<DisclosureBody> = emptyList(),
)

/**
 * document-service, over the shared `openbank-services` client-credentials token
 * (`document.business-agreement.ensure|read`). The request body is the port's
 * [BusinessAgreementRequest] verbatim — its JSON is the spec's.
 */
@Path("/api/v1/business-agreements")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "document-service")
// Below @RegisterRestClient deliberately, as in PartyServiceRestClient: an internal edge PROPAGATES
// the synthetic marker, so an agreement rendered for a synthetic case stays tainted downstream.
@RegisterProvider(SyntheticTaintClientFilter::class)
interface DocumentServiceRestClient {
    @POST
    suspend fun ensure(body: BusinessAgreementRequest): BusinessAgreementBody

    @GET
    @Path("/{caseId}")
    suspend fun get(@PathParam("caseId") caseId: UUID, @QueryParam("lang") lang: String): BusinessAgreementBody
}

@ApplicationScoped
class DocumentServiceGateway : DocumentGateway {

    @Inject @RestClient
    lateinit var client: DocumentServiceRestClient

    /** A 409 is document-service refusing to replace a SIGNED agreement — a conflict, not an outage. */
    @Timeout(DOCUMENT_TIMEOUT_MS)
    override suspend fun ensureBusinessAgreement(request: BusinessAgreementRequest): BusinessAgreementView = try {
        client.ensure(request).toView()
    } catch (e: WebApplicationException) {
        if (e.response?.status == HTTP_CONFLICT) {
            throw AgreementConflictException(
                AgreementConflictException.AGREEMENT_LOCKED,
                "document-service refused to replace an agreement that is already signed",
            )
        }
        throw e
    }

    /** 404 is "no agreement" (null); anything else propagates, so an outage never reads as "not signed". */
    @Timeout(DOCUMENT_TIMEOUT_MS)
    override suspend fun businessAgreement(caseId: UUID, lang: String): BusinessAgreementView? = try {
        client.get(caseId, lang).toView()
    } catch (e: WebApplicationException) {
        if (e.response?.status == HTTP_NOT_FOUND) null else throw e
    }

    private fun BusinessAgreementBody.toView() = BusinessAgreementView(
        caseId = caseId,
        documentId = documentId,
        templateCode = templateCode,
        templateVersion = templateVersion,
        sha256 = sha256,
        sealedSha256 = sealedSha256,
        ceremonyId = ceremonyId,
        ceremonyStatus = CeremonyStatus.parse(ceremonyStatus),
        signers = signers.map { CeremonySigner(it.partyRef, CeremonySignerStatus.parse(it.status), it.signedAt) },
        disclosures = disclosures.map { AgreementDisclosure(it.code, it.version, it.title, it.sha256, it.documentId) },
    )

    private companion object {
        const val DOCUMENT_TIMEOUT_MS = 10000L
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
    }
}
