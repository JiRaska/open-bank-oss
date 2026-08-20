// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

/**
 * Supplies the CALLER's own distributed-trace id, so a gateway-side Langfuse trace can be joined
 * to the service span that caused it (ADR-0265 slice 3 tail, #5671).
 *
 * Without this the two evidence trails do not meet. LiteLLM mints its own trace id per request, so
 * a Langfuse trace answers "what did the gateway see" and nothing links it to the copilot span, the
 * audit envelope or the Temporal run that produced the prompt. Reconstructing an incident then means
 * matching on wall-clock and model name, which is not an identifier.
 *
 * The second thing it buys is a FALSIFIABLE ingestion probe. Langfuse v2 exposes no Prometheus
 * endpoint and LiteLLM's callback metrics are an Enterprise feature, so "traces are landing" has had
 * no observable of any kind — a green Langfuse pod with a rejected callback key looks exactly like a
 * healthy one. When the caller chooses the id, the check becomes: issue a call carrying a known id,
 * then look that id up. Absence is then a negative result rather than an unanswerable question.
 *
 * [NONE] is the deliberate default for a caller that has no trace context. It returns null, and a
 * null id emits **no metadata at all** — see [isValidTraceId].
 */
fun interface TraceIdProvider {

    /** The current trace id, or null when there is no active trace. Must never throw. */
    fun currentTraceId(): String?

    companion object {
        /** No trace context — the safe default; callers that supply nothing stay as they were. */
        val NONE = TraceIdProvider { null }
    }
}

/**
 * True for a W3C trace id that identifies something: 32 lowercase hex characters, not all zeroes.
 *
 * The all-zero id is why this predicate exists rather than a null check. OpenTelemetry returns
 * `00000000000000000000000000000000` from an INVALID span context — an unsampled call, a thread with
 * no context, a service without the OTel extension — and it is a perfectly well-formed string. Sent
 * as `metadata.trace_id` it would collapse every untraced call in the fleet onto one shared Langfuse
 * trace, which is worse than sending nothing: the correlation would exist, be wrong, and look right.
 * Same family as this repo's `Instant.EPOCH` default — a sentinel that every reader accepts.
 */
fun isValidTraceId(traceId: String?): Boolean = traceId != null &&
    traceId.length == TRACE_ID_HEX_LENGTH &&
    traceId.all { it in '0'..'9' || it in 'a'..'f' } &&
    traceId.any { it != '0' }

private const val TRACE_ID_HEX_LENGTH = 32
