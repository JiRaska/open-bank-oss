// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.close

import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Day-close participation report to the ledger (ADR-0342 D1). Same base URL, TLS bucket and named
 * oidc-client (`m2m` = Keycloak `openbank-treasury`) as the journal client: the ledger admits the
 * report under the `ledger.create` grant that client already holds, then checks the caller is the
 * principal declared for participant `treasury` (ADR-0342 D2).
 */
@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/ledger/accounting-days")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface LedgerCloseParticipationClient {
    @PUT
    @Path("/{businessDate}/participants/{participant}")
    fun report(
        @PathParam("businessDate") businessDate: String,
        @PathParam("participant") participant: String,
        body: ParticipationReportRequest,
    ): Uni<Map<String, Any?>>
}

data class ParticipationReportRequest(
    val outcome: String,
    val journalsPosted: Int,
    val itemsProcessed: Int,
    val reason: String?,
)

/** Binds the libs-runtime [CloseParticipationSink] to [LedgerCloseParticipationClient]. */
@ApplicationScoped
class LedgerCloseParticipationSink(@RestClient private val client: LedgerCloseParticipationClient) :
    CloseParticipationSink {
    override fun deliver(participant: String, report: CloseParticipationReport): Uni<Unit> = client.report(
        businessDate = report.businessDate.toString(),
        participant = participant,
        body = ParticipationReportRequest(
            outcome = report.outcome.name,
            journalsPosted = report.journalsPosted,
            itemsProcessed = report.itemsProcessed,
            reason = report.reason?.take(MAX_REASON),
        ),
    ).replaceWith(Unit)

    private companion object {
        const val MAX_REASON = 500
    }
}
