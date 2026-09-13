// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
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
 * REST client for `openbank-transaction-service` (ADR-0108): books the settled funds after the
 * scheme confirms ACSC for an MT103. OIDC service-to-service auth is propagated by
 * [OidcClientRequestReactiveFilter] — same pattern as [ClearingSimulatorClient].
 */
@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/transactions")
interface TransactionServiceClient {
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun initiateTransaction(request: InitiateSettlementRequest): Uni<Response>
}

data class InitiateSettlementRequest(
    val idempotencyKey: String,
    val type: String,
    val sourceAccountId: UUID,
    val amount: BigDecimal,
    val currencyCode: String,
    val description: String,
    val valueDate: String,
    val rail: String,
    val instructionType: String? = null,
)
