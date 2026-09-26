// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
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
 * The outbound M2M token is minted by the named oidc-client `m2m`, standing-order-service's OWN
 * Keycloak client `openbank-standing-order` (ROLE_API only). sepa-payment's createPayment admits
 * ROLE_API at the RBAC layer and its OPA grants `sepaPayment.create` to this principal by identity
 * (#10486).
 */
@RegisterRestClient(configKey = "sepa-payment-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486: this client's bearer is minted by the NAMED oidc-client `m2m` - Keycloak client
// `openbank-standing-order` (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
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
