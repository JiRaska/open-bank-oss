// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * What happens to a held spend reservation once the rail has answered (ADR-0249 D3, #16000).
 *
 * The edge settles a reservation in exactly one place (`CustomerEdgeResource.settleDelegatedSpend`),
 * and that place is told which of these applies; nothing else on the edge confirms or releases.
 */
internal enum class ReservationDisposition {
    /** Legacy path only: the rail's 2xx consumes the headroom (over-counts a later rejection). */
    CONFIRM,

    /** The rail created nothing: give the headroom back now. */
    RELEASE,

    /**
     * Bound path: the payment exists (or may exist), so its terminal outcome settles the
     * reservation in delegation-service. Releasing here could free headroom for money that then
     * moves, which under-counts the ceiling — the unsafe direction.
     */
    LEAVE_TO_OUTCOME,
}

/** The customer-facing answer plus what to do with the reservation, and the rail's refusal code if any. */
internal data class DelegatedCreate(
    val response: Response,
    val disposition: ReservationDisposition,
    val railRefusalCode: String? = null,
)

/**
 * The reservation-bound create on domestic-payment (`POST /api/v1/domestic-payments/delegated`,
 * #16000 part 3), used only when `openbank.edge.delegated-payment-bound-create.enabled` is on.
 *
 * The rail refuses the payment unless the reservation is RESERVED and matches it, so the edge's
 * job is: send the reservation reference with the reservation's OWN Idempotency-Key (the rail
 * compares it), retry the two refusals that mean "not yet", and translate every other refusal into
 * a neutral answer that does not tell the caller which field disagreed.
 */
internal object DelegatedBoundCreate {
    const val PATH = "/api/v1/domestic-payments/delegated"
    const val MAX_ATTEMPTS = 3
    const val DEFAULT_WAIT_MS = 500L
    const val MAX_WAIT_MS = 2_000L
    private const val MS_PER_SECOND = 1_000L
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_CONFLICT = 409
    private const val HTTP_SERVER_ERROR = 500
    private const val HTTP_BAD_GATEWAY = 502
    private const val HTTP_UNAVAILABLE = 503
    private const val RETRY_AFTER_SECONDS = "5"

    private val RETRYABLE = setOf("RESERVATION_PROJECTION_PENDING", "ACCOUNT_AUTHORITY_UNAVAILABLE")

    /**
     * The rail's request: the edge-enriched instruction without `customerSubmissionScope` (the
     * reservation IS the delegate's replay binding there) plus the three binding ids.
     */
    fun boundBody(
        mapper: ObjectMapper,
        enriched: String,
        reservation: SpendReservation,
        actingPartyId: String,
    ): String? = runCatching {
        val node = mapper.readTree(enriched) as? ObjectNode ?: return null
        node.remove("customerSubmissionScope")
        node.put("reservationId", reservation.reservationId)
        node.put("delegationId", reservation.delegationId)
        node.put("actingPartyId", actingPartyId)
        mapper.writeValueAsString(node)
    }.getOrNull()

    @Suppress("LongParameterList")
    fun create(
        upstream: UpstreamClient,
        mapper: ObjectMapper,
        domesticPaymentServiceUrl: String,
        enriched: String,
        reservation: SpendReservation,
        actingPartyId: String,
        sleeper: (Long) -> Unit,
    ): DelegatedCreate {
        val body = boundBody(mapper, enriched, reservation, actingPartyId)
            ?: return DelegatedCreate(temporarilyUnavailable(HTTP_BAD_GATEWAY), ReservationDisposition.RELEASE)
        var attempt = 1
        while (true) {
            val resp = upstream.postKeepingRetryAfter(
                "$domesticPaymentServiceUrl$PATH",
                actingPartyId,
                body,
                reservation.idempotencyKey,
            )
            val status = resp.status
            if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) {
                return DelegatedCreate(resp, ReservationDisposition.LEAVE_TO_OUTCOME)
            }
            val code = refusalCode(mapper, resp)
            val retryable = code in RETRYABLE && (status == HTTP_CONFLICT || status == HTTP_UNAVAILABLE)
            when {
                retryable && attempt < MAX_ATTEMPTS -> {
                    sleeper(waitMillis(resp.getHeaderString("Retry-After")))
                    attempt++
                }
                // Definitively nothing was created: the rail said "not yet" every time we asked.
                retryable -> return DelegatedCreate(
                    temporarilyUnavailable(HTTP_UNAVAILABLE),
                    ReservationDisposition.RELEASE,
                    code,
                )
                // A 4xx refusal (coded or not): the rail created nothing. A 400 is about the caller's
                // own instruction and is passed through as the legacy route does; anything else
                // is neutral so a mismatch cannot be used to probe the reservation.
                status in HTTP_BAD_REQUEST until HTTP_SERVER_ERROR -> return DelegatedCreate(
                    if (status == HTTP_BAD_REQUEST) resp else notAccepted(),
                    ReservationDisposition.RELEASE,
                    code ?: "HTTP_$status",
                )
                // Any other 5xx / transport failure: the payment MAY exist. Leave the reservation to
                // the payment's outcome (or the rail's finalized-absent) rather than free headroom
                // for money that may still move. A retry with the same key replays on the rail.
                else -> return DelegatedCreate(
                    statusUnknown(),
                    ReservationDisposition.LEAVE_TO_OUTCOME,
                )
            }
        }
    }

    internal fun waitMillis(retryAfter: String?): Long = retryAfter?.trim()?.toLongOrNull()
        ?.let { (it * MS_PER_SECOND).coerceIn(0L, MAX_WAIT_MS) }
        ?: DEFAULT_WAIT_MS

    private fun refusalCode(mapper: ObjectMapper, resp: Response): String? =
        runCatching { mapper.readTree((resp.entity as? String).orEmpty()) }.getOrNull()
            ?.path("code")?.takeIf { it.isTextual }?.asText()

    private fun temporarilyUnavailable(status: Int): Response = Response.status(status)
        .header("Retry-After", RETRY_AFTER_SECONDS)
        .entity(
            """{"error":"The payment could not be submitted right now. Please try again shortly.",""" +
                """"code":"PAYMENT_TEMPORARILY_UNAVAILABLE"}""",
        )
        .type(MediaType.APPLICATION_JSON)
        .build()

    /** The payment may exist: the same Idempotency-Key replays it, a new one could pay twice. */
    private fun statusUnknown(): Response = Response.status(HTTP_UNAVAILABLE)
        .header("Retry-After", RETRY_AFTER_SECONDS)
        .entity(
            """{"error":"We could not confirm the payment. Retry with the same Idempotency-Key.",""" +
                """"code":"PAYMENT_STATUS_UNKNOWN"}""",
        )
        .type(MediaType.APPLICATION_JSON)
        .build()

    private fun notAccepted(): Response = Response.status(HTTP_CONFLICT)
        .entity("""{"error":"The payment could not be submitted.","code":"PAYMENT_NOT_ACCEPTED"}""")
        .type(MediaType.APPLICATION_JSON)
        .build()
}
