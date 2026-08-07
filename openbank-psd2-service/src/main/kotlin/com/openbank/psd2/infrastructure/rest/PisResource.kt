// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.psd2.application.port.`in`.GetPaymentStatusQuery
import com.openbank.psd2.application.port.`in`.InitiatePaymentCommand
import com.openbank.psd2.application.port.`in`.PaymentInitiationUseCase
import com.openbank.psd2.application.usecase.Psd2RequestFormatException
import com.openbank.psd2.domain.model.DomesticCzPayment
import com.openbank.psd2.domain.model.PaymentInitiation
import com.openbank.psd2.domain.model.PaymentProduct
import com.openbank.psd2.domain.model.SipoPayment
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/open-banking/v2/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class PisResource(
    private val pis: PaymentInitiationUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {
    @POST
    @Path("/sepa-credit-transfers")
    @Authorize(action = "psd2.initiate", resource = "")
    suspend fun initiateSepa(
        payment: PaymentInitiation,
        @HeaderParam("Consent-ID") consentId: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @Context ctx: ContainerRequestContext,
    ): Response = initiatePayment(payment, consentId, idempotencyKey, PaymentProduct.SEPA_CREDIT_TRANSFERS, ctx)

    @POST
    @Path("/instant-sepa-credit-transfers")
    @Authorize(action = "psd2.initiate", resource = "")
    suspend fun initiateInstantSepa(
        payment: PaymentInitiation,
        @HeaderParam("Consent-ID") consentId: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @Context ctx: ContainerRequestContext,
    ): Response = initiatePayment(payment, consentId, idempotencyKey, PaymentProduct.INSTANT_SEPA_CREDIT_TRANSFERS, ctx)

    @POST
    @Path("/domestic-cz")
    @Authorize(action = "psd2.initiate", resource = "")
    suspend fun initiateDomesticCz(
        payment: DomesticCzPayment,
        @HeaderParam("Consent-ID") consentId: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @Context ctx: ContainerRequestContext,
    ): Response = initiatePayment(payment, consentId, idempotencyKey, PaymentProduct.DOMESTIC_CZ, ctx)

    @POST
    @Path("/sipo")
    @Authorize(action = "psd2.initiate", resource = "")
    suspend fun initiateSipo(
        payment: SipoPayment,
        @HeaderParam("Consent-ID") consentId: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @Context ctx: ContainerRequestContext,
    ): Response = initiatePayment(payment, consentId, idempotencyKey, PaymentProduct.SIPO, ctx)

    @GET
    @Path("/{product}/{paymentId}/status")
    @Authorize(action = "psd2.read", resource = "#paymentId")
    suspend fun getStatus(
        @PathParam("product") product: String,
        @PathParam("paymentId") paymentId: String,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        val pp = runCatching { PaymentProduct.valueOf(product.uppercase().replace("-", "_")) }
            .getOrElse { return Response.status(404).build() }
        val status = pis.getPaymentStatus(GetPaymentStatusQuery(paymentId, tppId, pp))
        return Response.ok(mapOf("transactionStatus" to status.name)).build()
    }

    /**
     * The four initiation handlers differ only in payment product, so both Berlin Group headers are
     * validated once, here — the single place every one of them passes through.
     *
     * #3624: both used to be declared non-nullable, which bought nothing at runtime. These are
     * `suspend` handlers, so Kotlin emits no `Intrinsics.checkNotNullParameter` and JAX-RS's null
     * for an ABSENT header flowed straight in. `Consent-ID` then reached `InitiatePaymentCommand`
     * as null, and `idempotencyKey.isBlank()` threw NPE — so the guard below answered 500 in
     * exactly the case it was written for, while a BLANK header correctly gave 400.
     */
    private suspend fun initiatePayment(
        payment: Any,
        consentId: String?,
        idempotencyKey: String?,
        product: PaymentProduct,
        ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        if (consentId.isNullOrBlank()) throw Psd2RequestFormatException("Consent-ID header is required")
        if (idempotencyKey.isNullOrBlank()) throw Psd2RequestFormatException("Idempotency-Key header is required")

        val cacheKey = paymentCreateKey(tppId, product, idempotencyKey)
        idempotencyStore.get(cacheKey)?.let { cached ->
            return Response.status(cached.statusCode)
                .entity(cached.responseBody)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Replayed", "true")
                .build()
        }

        val result = pis.initiatePayment(
            InitiatePaymentCommand(tppId, consentId, product, payment, idempotencyKey),
        )
        idempotencyStore.save(cacheKey, 201, objectMapper.writeValueAsString(result))
        return Response.status(201).entity(result).build()
    }

    private fun tppMissing() = Response.status(401)
        .entity(mapOf("tppMessages" to listOf(mapOf("category" to "ERROR", "code" to "CERTIFICATE_MISSING")))).build()

    private fun paymentCreateKey(tppId: String, product: PaymentProduct, idempotencyKey: String) =
        "psd2:payment:$tppId:${product.name}:$idempotencyKey"
}
