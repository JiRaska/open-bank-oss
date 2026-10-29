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
 * Day-close participation report to ledger-service (ADR-0342 D1). Same base URL, TLS bucket and
 * NAMED oidc-client `m2m` (Keycloak `openbank-treasury`, ROLE_API only) as the journal client. The
 * ledger admits the report under its own action `ledger.close.participate`, for the participant
 * `treasury` only (`ledger_rest_ext.rego: close_participant_accounts`), then checks the verified
 * caller against `openbank.ledger.close.participants.treasury.principal` (ADR-0342 D2).
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
            reason = report.reason,
        ),
    ).replaceWith(Unit)
}
