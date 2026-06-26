// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.psd2.domain.model.ConsentStatusOb
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObAccountRef
import com.openbank.psd2.domain.model.ObAmount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObConsentRequest
import com.openbank.psd2.domain.model.ObConsentResponse
import com.openbank.psd2.domain.model.ObLinks
import com.openbank.psd2.domain.model.ObTransaction
import com.openbank.psd2.domain.model.PaymentInitiationResponse
import com.openbank.psd2.domain.model.PaymentStatus
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

@Path("/open-banking/sandbox/v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SandboxResource(private val clock: Clock) {

    @GET
    @Path("/accounts")
    fun sandboxAccounts(@HeaderParam("Consent-ID") consentId: String?): Response =
        Response.ok(
            mapOf(
                "accounts" to listOf(
                    ObAccount(
                        "sandbox-acc-001",
                        "CZ6508000000192000145399",
                        "CZK",
                        "Sandbox User",
                        "Sandbox Account",
                        "CURRENT",
                        "CACC",
                    ),
                    ObAccount(
                        "sandbox-acc-002",
                        "CZ6508000000192000145400",
                        "EUR",
                        "Sandbox User",
                        "EUR Account",
                        "CURRENT",
                        "CACC",
                    ),
                ),
            ),
        ).build()

    @GET
    @Path("/accounts/{accountId}/balances")
    fun sandboxBalances(@PathParam("accountId") accountId: String): Response = Response.ok(
        mapOf(
            "balances" to listOf(
                ObBalance(
                    ObAmount("CZK", BigDecimal("50000.00")),
                    "closingBooked",
                    OffsetDateTime.now(clock),
                    LocalDate.now(clock),
                ),
                ObBalance(
                    ObAmount("CZK", BigDecimal("48000.00")),
                    "expected",
                    OffsetDateTime.now(clock),
                    LocalDate.now(clock),
                ),
            ),
        ),
    ).build()

    @GET
    @Path("/accounts/{accountId}/transactions")
    fun sandboxTransactions(@PathParam("accountId") accountId: String): Response = Response.ok(
        mapOf(
            "transactions" to mapOf(
                "booked" to listOf(
                    ObTransaction(
                        transactionId = "sandbox-tx-001",
                        entryReference = "REF001",
                        bookingDate = LocalDate.now(clock).minusDays(1),
                        valueDate = LocalDate.now(clock).minusDays(1),
                        transactionAmount = ObAmount("CZK", BigDecimal("-1500.00")),
                        creditorName = "Sandbox Merchant",
                        creditorAccount = ObAccountRef(
                            iban = "CZ6508000000192000145401",
                            bban = null,
                            pan = null,
                            maskedPan = null,
                            msisdn = null,
                            currency = null,
                        ),
                        debtorName = null,
                        debtorAccount = null,
                        remittanceInformationUnstructured = "Sandbox payment",
                        bankTransactionCode = "PMNT",
                        bookingStatus = "BOOKED",
                    ),
                ),
                "pending" to emptyList<ObTransaction>(),
            ),
        ),
    ).build()

    @POST
    @Path("/consents")
    fun sandboxConsent(request: ObConsentRequest): Response = Response.status(201).entity(
        ObConsentResponse(
            consentId = "sandbox-consent-${System.currentTimeMillis()}",
            consentStatus = ConsentStatusOb.VALID,
            access = request.access,
            recurringIndicator = request.recurringIndicator,
            validUntil = request.validUntil,
            frequencyPerDay = request.frequencyPerDay,
            lastActionDate = LocalDate.now(clock),
            links = ObLinks(
                self = "/open-banking/sandbox/v2/consents/sandbox-consent-001",
                status = "/open-banking/sandbox/v2/consents/sandbox-consent-001/status",
            ),
        ),
    ).build()

    @POST
    @Path("/payments/{product}")
    fun sandboxPayment(
        @PathParam("product") product: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response = Response.status(201).entity(
        PaymentInitiationResponse(
            paymentId = "sandbox-pay-${System.currentTimeMillis()}",
            transactionStatus = PaymentStatus.RCVD,
            scaStatus = "received",
            links = ObLinks(
                self = "/open-banking/sandbox/v2/payments/$product/sandbox-pay-001",
                status = "/open-banking/sandbox/v2/payments/$product/sandbox-pay-001/status",
            ),
        ),
    ).build()

    @GET
    @Path("/health")
    fun health(): Response = Response.ok(mapOf("status" to "UP", "sandbox" to true)).build()
}
