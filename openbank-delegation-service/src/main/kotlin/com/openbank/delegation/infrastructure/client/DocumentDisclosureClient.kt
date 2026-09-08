// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.infrastructure.client

import com.openbank.delegation.application.port.out.ExternalDisclosureArtifact
import com.openbank.delegation.application.port.out.ExternalDisclosureDocumentExporter
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Instant
import java.util.UUID

data class DocumentDisclosureExportRequest(val disclosureId: UUID, val recipientLabel: String, val issuedAt: Instant)

/**
 * This is the only client using the named `disclosure` OIDC client. Do not replace it with the
 * default reactive filter: that filter carries the fleet-wide subject, which document-service
 * intentionally denies for `document.disclosure.export`.
 */
@Path("/api/v1/documents")
@OidcClientFilter("disclosure")
@RegisterRestClient(configKey = "document-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface DocumentServiceDisclosureRestClient {
    @POST
    @Path("/{documentId}/external-disclosures/export")
    fun export(@PathParam("documentId") documentId: UUID, request: DocumentDisclosureExportRequest): Response
}

@ApplicationScoped
class RestExternalDisclosureDocumentExporter @Inject constructor(
    @RestClient private val client: DocumentServiceDisclosureRestClient,
) : ExternalDisclosureDocumentExporter {

    @Timeout(3000)
    @Retry(maxRetries = 1, delay = 100, jitter = 50, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    override suspend fun export(
        documentId: UUID,
        disclosureId: UUID,
        recipientLabel: String,
        issuedAt: Instant,
    ): ExternalDisclosureArtifact = client.export(
        documentId,
        DocumentDisclosureExportRequest(disclosureId, recipientLabel, issuedAt),
    ).use { response ->
        if (response.statusInfo.family != Response.Status.Family.SUCCESSFUL) {
            throw IllegalStateException("sealed disclosure export failed with HTTP ${response.status}")
        }
        ExternalDisclosureArtifact(
            contentType = response.mediaType?.toString() ?: "application/pdf",
            bytes = response.readEntity(ByteArray::class.java),
        )
    }
}
