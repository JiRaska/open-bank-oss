// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * REST client for `openbank-sepa-payment-service`'s create endpoint (#889).
 *
 * A standing order that is due publishes `standing-order.due.v1`; [com.openbank.standingorder.infrastructure.kafka.StandingOrderDueConsumer]
 * consumes it and, for a `SEPA_CREDIT` order, initiates the actual credit transfer here. The
 * `Idempotency-Key` is the deterministic `so-exec-{orderId}-{executionDate}` carried on the event,
 * so a Kafka redelivery replays the same payment (sepa-payment returns the cached 201) rather than
 * paying twice.
 *
 * The outbound M2M token is minted by the `openbank-services` oidc-client (ROLE_OPERATOR), which
 * sepa-payment's createPayment accepts (`@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")`).
 */
@RegisterRestClient(configKey = "sepa-payment-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter::class)
interface SepaPaymentClient {

    @POST
    @Path("/api/v1/sepa-payments")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun createPayment(
        @HeaderParam("Idempotency-Key") idempotencyKey: String,
        request: CreateSepaPaymentRequest,
    ): Uni<Response>
}

/**
 * Wire shape of sepa-payment's `CreateSepaPaymentRequest`. `type` is always `SCT` (SEPA Credit
 * Transfer) for a standing order; `SCT_INST` (instant) is a separate rail not driven from here.
 */
data class CreateSepaPaymentRequest(
    val type: String,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val debtorName: String,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amount: BigDecimal,
    val currency: String,
    val remittanceInfo: String?,
    val endToEndId: String?,
)
