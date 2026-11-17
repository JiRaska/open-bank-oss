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
        // Nothing was sent, so nothing can be bound — but a replayed reservation may already be.
        val body = boundBody(mapper, enriched, reservation, actingPartyId)
            ?: return DelegatedCreate(
                temporarilyUnavailable(HTTP_BAD_GATEWAY),
                if (reservation.replayed) ReservationDisposition.LEAVE_TO_OUTCOME else ReservationDisposition.RELEASE,
            )
        var attempt = 1
        while (true) {
            val resp = upstream.postKeepingRetryAfter(
                "$domesticPaymentServiceUrl$PATH",
                actingPartyId,
                body,
                reservation.idempotencyKey,
            )
            if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) {
                return DelegatedCreate(resp, ReservationDisposition.LEAVE_TO_OUTCOME)
            }
            val code = refusalCode(mapper, resp)
            val retryable = code in RETRYABLE && (resp.status == HTTP_CONFLICT || resp.status == HTTP_UNAVAILABLE)
            if (retryable && attempt < MAX_ATTEMPTS) {
                sleeper(waitMillis(resp.getHeaderString("Retry-After")))
                attempt++
                continue
            }
            return DelegatedCreate(
                customerAnswer(resp, code, retryable),
                dispositionForRefusal(code, reservation.replayed),
                code ?: "HTTP_${resp.status}",
            )
        }
    }

    /** The neutral answer the customer sees for a refusal; never names the rail's reason. */
    private fun customerAnswer(resp: Response, code: String?, retryable: Boolean): Response = when {
        retryable -> temporarilyUnavailable(HTTP_UNAVAILABLE)
        // A 400 is about the caller's own instruction; passed through as the legacy route does.
        resp.status == HTTP_BAD_REQUEST && code == null -> resp
        resp.status in HTTP_BAD_REQUEST until HTTP_SERVER_ERROR -> notAccepted()
        // Any other 5xx / transport failure: the payment MAY exist; the same key replays it.
        else -> statusUnknown()
    }

    /**
     * The ONE decision on whether the edge may give a reservation back after the rail refused
     * (#16000). Releasing is safe only when the rail PROVES nothing was bound, and only for a
     * reservation this request created itself: a replayed reservation may already be bound to a
     * payment by an earlier attempt, and releasing it would free headroom for money that can still
     * move. Everything else is left to delegation-service, which settles from the payment's
     * terminal event or the rail's finalized-absent event. Unknown code -> never release.
     *
     * Which codes are pre-binding, read from domestic-payment's `createDelegatedPayment`:
     * - RESERVATION_PROJECTION_PENDING — no binding row exists at all (lookup miss, or the
     *   repository's ProjectionMissing).
     * - RESERVATION_MISMATCH — returned by the pre-checks, or by the repository only when the
     *   binding is NOT already BOUND (a BOUND one answers a replay instead).
     * - ACCOUNT_AUTHORITY_UNAVAILABLE — returned before the repository is reached.
     * - DELEGATION_AUTHORITY_WITHDRAWN — the ADR-0375 D1 create-time authority step
     *   (`refuseDelegatedAuthority`) refused; it runs before `createPaymentInternal`, so neither a
     *   binding nor a payment is written. Not retryable: without this release the headroom stays
     *   held for a grant that can no longer spend it, until delegation-service settles it.
     * NOT pre-binding proof: RESERVATION_NOT_RESERVED (already finalized; delegation settles it),
     * IDEMPOTENCY_KEY_REUSED (a payment exists under this key), and every uncoded status.
     */
    internal fun dispositionForRefusal(code: String?, reservationReplayed: Boolean): ReservationDisposition {
        val provesNothingBound = when (code) {
            "RESERVATION_PROJECTION_PENDING" -> true
            "RESERVATION_MISMATCH" -> true
            "ACCOUNT_AUTHORITY_UNAVAILABLE" -> true
            "DELEGATION_AUTHORITY_WITHDRAWN" -> true
            "RESERVATION_NOT_RESERVED" -> false
            "IDEMPOTENCY_KEY_REUSED" -> false
            else -> false
        }
        return if (provesNothingBound && !reservationReplayed) {
            ReservationDisposition.RELEASE
        } else {
            ReservationDisposition.LEAVE_TO_OUTCOME
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
