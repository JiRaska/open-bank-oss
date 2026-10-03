// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.lending.application.port.out.LoanEvidence
import com.openbank.lending.application.port.out.LoanEvidenceEvent
import com.openbank.lending.application.port.out.LoanEvidencePort
import com.openbank.lending.application.port.out.LoanEvidenceUnavailable
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Instant

/**
 * audit-service's ADR-0214 D3 evidence route (#11900). Deliberately NOT `@OidcClientFilter`: the
 * call carries the signed-in person's own token ([CallerTokenClientHeadersFactory]), so audit-service
 * sees — and records — the human, and no service account of this service is ever granted the trail.
 */
@RegisterRestClient(configKey = "audit-service")
@RegisterClientHeaders(CallerTokenClientHeadersFactory::class)
// ADR-0252: an internal edge, so a synthetic request's taint must reach audit-service too.
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
interface AuditEvidenceRestClient {
    @GET
    @Path("/evidence/{aggregateId}")
    fun evidence(@PathParam("aggregateId") aggregateId: String): Uni<AuditEvidenceResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuditEvidenceResponse(
    val attestation: String,
    val truncated: Boolean,
    val tampered: Boolean,
    val entries: List<AuditEvidenceEntry> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuditEvidenceEntry(
    val entryId: String,
    val eventType: String,
    val sourceService: String,
    val occurredAt: Instant,
    val payload: String,
    val hashStatus: String,
)

@ApplicationScoped
class AuditChainLoanEvidenceAdapter(@RestClient private val client: AuditEvidenceRestClient) : LoanEvidencePort {
    override suspend fun bundleFor(applicationId: String): LoanEvidence {
        val response = try {
            client.evidence(applicationId).awaitSuspending()
        } catch (e: WebApplicationException) {
            throw LoanEvidenceUnavailable(e.response.status, "audit-service refused the evidence read", e)
        } catch (e: ProcessingException) {
            throw LoanEvidenceUnavailable(null, "audit-service unreachable", e)
        }
        return LoanEvidence(
            attestation = response.attestation,
            truncated = response.truncated,
            tampered = response.tampered,
            events = response.entries.map {
                LoanEvidenceEvent(it.entryId, it.eventType, it.sourceService, it.occurredAt, it.payload, it.hashStatus)
            },
        )
    }
}
