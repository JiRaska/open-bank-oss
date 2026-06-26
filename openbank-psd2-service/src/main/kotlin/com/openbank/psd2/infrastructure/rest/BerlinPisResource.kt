// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.psd2.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.psd2.application.port.`in`.GetPaymentStatusQuery
import com.openbank.psd2.application.port.`in`.InitiatePaymentCommand
import com.openbank.psd2.application.port.`in`.PaymentInitiationUseCase
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

/**
 * Berlin Group NextGenPSD2 XS2A 1.3.12 — Payment Initiation (PIS) endpoints (ADR-0090 P2).
 *
 * Reuses [PaymentInitiationUseCase]; only path/headers/wire shape are Berlin-conformant
 * ([BerlinXs2aMappers]). This is the **money-path** surface — TPP auth + PISP role are enforced
 * upstream by EidasMtlsFilter (the `/payments` path segment selects the PISP role), and the use
 * case validates a payment-authorising consent before delegating to transaction-service.
 *
 * P2 shipped the pan-EU Berlin SEPA products; P3 layers the Czech **ČOBS** products on the same
 * path — `domestic-cz` (VS/SS/KS symbols) and `sipo` — by deserialising the request body to the
 * product-specific shape. Single-payment `POST` + `GET …/status` per spec; payment-information
 * `GET` and QSEAL signing follow in P4.
 */
@Path("/v1/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class BerlinPisResource(
    private val pis: PaymentInitiationUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {
    @POST
    @Path("/{paymentProduct}")
    @Authorize(action = "psd2.initiate", resource = "")
    suspend fun initiate(
        @PathParam("paymentProduct") paymentProduct: String,
        body: JsonNode,
        @HeaderParam("Consent-ID") consentId: String?,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        val product = BerlinXs2aMappers.productOf(paymentProduct) ?: return productNotSupported()
        if (consentId.isNullOrBlank()) return missingConsentId()
        if (xRequestId.isNullOrBlank()) return missingRequestId()
        val payment = runCatching { deserialize(product, body) }.getOrElse { return malformedBody() }

        val cacheKey = "psd2:v1:payment:$tppId:${product.name}:$xRequestId"
        idempotencyStore.get(cacheKey)?.let { cached ->
            return Response.status(cached.statusCode)
                .entity(cached.responseBody)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Request-ID", xRequestId)
                .header("X-Idempotency-Replayed", "true")
                .build()
        }

        val result = pis.initiatePayment(InitiatePaymentCommand(tppId, consentId, product, payment, xRequestId))
        val body = BerlinXs2aMappers.paymentInitiated(product, result)
        idempotencyStore.save(cacheKey, Response.Status.CREATED.statusCode, objectMapper.writeValueAsString(body))
        return Response.status(Response.Status.CREATED)
            .header("X-Request-ID", xRequestId)
            .header("Location", "/v1/payments/${BerlinXs2aMappers.productSegment(product)}/${result.paymentId}")
            .entity(body)
            .build()
    }

    @GET
    @Path("/{paymentProduct}/{paymentId}/status")
    @Authorize(action = "psd2.read", resource = "#paymentId")
    suspend fun getStatus(
        @PathParam("paymentProduct") paymentProduct: String,
        @PathParam("paymentId") paymentId: String,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        val product = BerlinXs2aMappers.productOf(paymentProduct) ?: return productNotSupported()
        val status = pis.getPaymentStatus(GetPaymentStatusQuery(paymentId, tppId, product))
        return echo(xRequestId).entity(BerlinXs2aMappers.paymentStatus(status)).build()
    }

    /** Bind the JSON body to the product-specific shape — ČOBS products carry VS/SS/KS / SIPO fields. */
    private fun deserialize(product: PaymentProduct, body: JsonNode): Any = when (product) {
        PaymentProduct.SEPA_CREDIT_TRANSFERS,
        PaymentProduct.INSTANT_SEPA_CREDIT_TRANSFERS,
        -> objectMapper.treeToValue(body, PaymentInitiation::class.java)
        PaymentProduct.DOMESTIC_CZ -> objectMapper.treeToValue(body, DomesticCzPayment::class.java)
        PaymentProduct.SIPO -> objectMapper.treeToValue(body, SipoPayment::class.java)
    }

    private fun echo(xRequestId: String?): Response.ResponseBuilder =
        Response.ok().apply { xRequestId?.let { header("X-Request-ID", it) } }

    private fun tppMissing() = Response.status(Response.Status.UNAUTHORIZED)
        .entity(BerlinXs2aMappers.tppError("CERTIFICATE_MISSING")).build()

    private fun missingConsentId() = Response.status(Response.Status.UNAUTHORIZED)
        .entity(BerlinXs2aMappers.tppError("CONSENT_INVALID", "Consent-ID header is mandatory")).build()

    private fun missingRequestId() = Response.status(Response.Status.BAD_REQUEST)
        .entity(BerlinXs2aMappers.tppError("FORMAT_ERROR", "X-Request-ID header is mandatory")).build()

    private fun productNotSupported() = Response.status(Response.Status.NOT_FOUND)
        .entity(BerlinXs2aMappers.tppError("PRODUCT_UNKNOWN", "Unknown payment product")).build()

    private fun malformedBody() = Response.status(Response.Status.BAD_REQUEST)
        .entity(BerlinXs2aMappers.tppError("FORMAT_ERROR", "Request body does not match the payment product")).build()
}
