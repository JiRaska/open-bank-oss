// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.infrastructure.clickhouse.ClickHouseClient
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * The ADR-0269 credit profile for one party (issue #6215).
 *
 * Reads `gold_party_credit_profile` — the single definition of these numbers (see V9). Three
 * consumers need them and each would otherwise derive its own: the creditworthiness assessment, the
 * customer's financial-health view, and the AI advisor.
 *
 * ## What this returns and what it refuses to
 *
 * Observable quantities only: median monthly in, median monthly out, net, a volatility ratio and how
 * many months were actually observed. **No score, no rating, no decision, no eligibility.** Those
 * are lending-service's (ADR-0213), and the separation is what lets a threshold move without the
 * measurement moving underneath it.
 *
 * ## `monthsObserved` is part of the answer, not metadata
 *
 * A three-week-old customer and a five-year customer can produce the same median. Returning the
 * count forces every caller to decide what it does with a thin history instead of quietly treating
 * "we have barely seen you" as "you earn this". lending-service's gate reads it and refuses to
 * treat an under-observed profile as evidence.
 *
 * ## Access
 *
 * Internal service-to-service. `Roles.OPERATOR` is the role an M2M client-credentials token carries
 * in this fleet (the same one customer-edge presents to lending); the auditor and admin roles are
 * here for the reason ReconciliationResource carries them — this is the data a creditworthiness
 * decision was made on, so an auditor must be able to read it back.
 */
@Path("/api/v1/analytics/credit-profile")
@Produces(MediaType.APPLICATION_JSON)
class CreditProfileResource {

    @Inject lateinit var clickHouse: ClickHouseClient

    @Inject lateinit var objectMapper: ObjectMapper

    @GET
    @Path("/{partyId}")
    @RolesAllowed(Roles.OPERATOR, Roles.AUDITOR, Roles.ADMIN)
    suspend fun profile(@PathParam("partyId") partyId: String): Response {
        // The UUID parse IS the injection boundary — the value is interpolated into SQL below.
        // ClickHouse's HTTP interface takes the query as a string, so there is no bound-parameter
        // path to fall back on; a rejected non-UUID is the control, not a convenience.
        val party = runCatching { UUID.fromString(partyId) }.getOrNull()
            ?: return Response.status(BAD_REQUEST).entity(mapOf("error" to "partyId is not a UUID")).build()

        val json = clickHouse.query(
            """
            SELECT months_observed, income_monthly, outflow_monthly, net_monthly,
                   volatility_ratio, movements_observed
            FROM ${'$'}{DB}.gold_party_credit_profile
            WHERE party_id = '$party'
            FORMAT JSONEachRow
            """.trimIndent().replace("\${DB}", DATABASE),
        )

        val row = json.lineSequence().firstOrNull { it.isNotBlank() }
            // No row is not an error and not a zero-income customer: it is a party with no observed
            // months. Answering with zeros would be indistinguishable from someone whose income really
            // is zero, and a credit gate cannot tell those apart afterwards.
            ?: return Response.ok(EMPTY_PROFILE).build()

        return Response.ok(objectMapper.readTree(row)).build()
    }

    companion object {
        private const val DATABASE = "openbank_analytics"
        private const val BAD_REQUEST = 400

        /** monthsObserved = 0 is the honest shape of "we have not seen this party". */
        private val EMPTY_PROFILE = mapOf(
            "months_observed" to 0,
            "income_monthly" to null,
            "outflow_monthly" to null,
            "net_monthly" to null,
            "volatility_ratio" to null,
            "movements_observed" to 0,
        )
    }
}
