// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Resolves a `creditorIban` to an internal `accountId` (`GET /api/v1/accounts/iban/{iban}`) — the
 * same lookup `openbank-domestic-payment`'s `AccountServiceClient` uses to tell an in-house payee
 * from a genuinely external one. A `DOMESTIC`/`INTERNAL` standing order whose creditor resolves
 * here never leaves the bank, so [StandingOrderDueConsumer] routes it straight to
 * transaction-service instead of the CZ clearing rail — the same rule
 * `com.openbank.libs.domain.payment.SettlementScope` encodes for same-day booking.
 *
 * `Uni<Response>`, not a typed 404-throws-exception client, to match this file's own
 * [SepaPaymentClient] style: 404 (no such IBAN, or genuinely a different bank) is read as a normal
 * answer here, not an error — resolved to `null`, not retried or logged loud.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
interface AccountServiceClient {
    @GET
    @Path("/iban/{iban}")
    fun getByIban(@PathParam("iban") iban: String): Uni<Response>
}
