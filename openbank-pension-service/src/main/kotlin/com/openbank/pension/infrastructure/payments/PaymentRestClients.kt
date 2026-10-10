// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/*
 * Outbound REST clients of the payment adapters (#12378). Each one names only the routes this
 * service calls, with the request shape of the provider's openapi.yaml on origin/main. All three
 * authenticate with pension-service's OWN client-credentials token (Keycloak client
 * `openbank-pension`, ROLE_API only) — never the shared openbank-services account.
 */

/** standing-order-service (openapi 1.8.0): createStandingOrder, cancelStandingOrder. */
@Path("/api/v1/standing-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "standing-order-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface StandingOrderRestClient {
    @POST
    suspend fun create(request: CreateStandingOrderDto): StandingOrderDto

    @DELETE
    @Path("/{id}")
    suspend fun cancel(@PathParam("id") id: UUID)
}

/** sdd-service: register a debtor mandate (idempotent on CID + UMR) and cancel it. */
@Path("/api/v1/sdd/mandates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "sdd-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface SddMandateRestClient {
    @POST
    suspend fun register(request: RegisterSddMandateDto): SddMandateDto

    @POST
    @Path("/{id}/cancel")
    suspend fun cancel(@PathParam("id") id: UUID): SddMandateDto
}

/** domestic-payment: createDomesticPayment (Idempotency-Key header required). */
@Path("/api/v1/domestic-payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "domestic-payment")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface DomesticPaymentRestClient {
    @POST
    suspend fun create(
        @HeaderParam("Idempotency-Key") idempotencyKey: String,
        request: CreateDomesticPaymentDto,
    ): DomesticPaymentDto
}

/** Mirrors standing-order-service's CreateStandingOrderRequest: nullable-without-default fields are sent as null. */
data class CreateStandingOrderDto(
    val idempotencyKey: String,
    val partyId: UUID,
    val debitAccountId: UUID,
    val debtorIban: String?,
    val debtorName: String?,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amountMinorUnits: Long,
    val currency: String,
    val frequency: String,
    val paymentType: String,
    val remittanceInfo: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StandingOrderDto(val id: UUID, val status: String? = null)

data class RegisterSddMandateDto(
    /**
     * The debtor party (#12419, ADR-0335): sdd-service asks account-service that THIS party owns
     * the debtor IBAN and that its id is [accountId] before it accepts a scoped initiator's mandate.
     */
    val partyId: UUID,
    val accountId: UUID,
    val debtorIban: String,
    val creditorIdentifier: String,
    val umr: String,
    val scheme: String,
    val sequenceType: String,
    val creditorName: String,
    val debtorName: String,
    val signatureDate: LocalDate,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SddMandateDto(val id: UUID, val status: String? = null)

data class CreateDomesticPaymentDto(
    val debtorAccountId: UUID,
    val debtorAccountNumber: String,
    val debtorBankCode: String,
    val debtorName: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val creditorName: String,
    val amount: BigDecimal,
    val currency: String,
    val priority: String,
    val messageForPayee: String?,
    val endToEndId: String?,
    // Present-but-null: the provider's DTO declares these nullable WITHOUT a default.
    val variableSymbol: String? = null,
    val specificSymbol: String? = null,
    val constantSymbol: String? = null,
    val statementLabel: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DomesticPaymentDto(val id: UUID, val status: String? = null)
