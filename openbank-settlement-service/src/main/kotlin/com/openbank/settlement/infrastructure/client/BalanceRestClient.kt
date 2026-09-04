// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

@RegisterRestClient(configKey = "balance-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/balances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface BalanceRestClient {
    @POST
    @Path("/{accountId}/debit")
    fun debit(@PathParam("accountId") accountId: UUID, body: MoneyMovementRequest): Uni<BalanceResponse>

    @POST
    @Path("/{accountId}/credit")
    fun credit(@PathParam("accountId") accountId: UUID, body: MoneyMovementRequest): Uni<BalanceResponse>
}

data class MoneyMovementRequest(
    val amount: BigDecimal,
    val currency: String,
    val referenceId: String,
    val description: String,
)

/**
 * The subset of balance-service's balance payload settlement binds — a local mirror, never a shared
 * type.
 *
 * The field names are balance-service's own (`Balance`: `availableAmount`, `bookedAmount`), pinned
 * by `SettlementBalanceMovementPactConsumerTest` and replayed against the running provider by
 * `BalancePactFolderProviderVerificationTest`. They used to read `availableBalance`/`currentBalance`
 * — names balance-service has never emitted — and both were non-nullable, so Jackson-Kotlin would
 * have raised `MissingKotlinParameterException` on every real `credit`/`debit` response and failed
 * the movement *after* balance-service had already applied it (#8345).
 *
 * Nothing caught it because nothing on the path ever saw the real payload: both adapters are unit
 * tested against a `mockk` client, and `BalanceServiceWireMockResource` — the stub that exists
 * precisely so "an actual HTTP request leaves the process" (#6037) — returned the same invented
 * shape, so the one test written to be un-mockable agreed with the defect.
 *
 * The amounts are nullable and unknown fields are ignored on purpose: neither adapter reads them
 * (they act on the HTTP status), so a money movement must not fail on a cosmetic change to a
 * response body it does not consume. The contract, not the DTO, is what pins the names.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BalanceResponse(
    val accountId: UUID,
    val currency: String,
    val availableAmount: BigDecimal? = null,
    val bookedAmount: BigDecimal? = null,
)
