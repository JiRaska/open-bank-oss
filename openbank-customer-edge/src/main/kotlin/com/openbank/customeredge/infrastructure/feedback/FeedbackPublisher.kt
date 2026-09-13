// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.feedback

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.logging.Log
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.OnOverflow
import java.time.Clock
import java.time.Instant

/**
 * One screen-feedback submission, already validated and (if a screenshot was sent) already
 * stored in object storage — this is the METADATA that travels on Kafka.
 *
 * [screenshotKey] is an object-store key, never image bytes: the PNG stays in S3 under a
 * 90-day lifecycle rule and only this opaque reference enters the event stream and the
 * warehouse (ADR-0192). A null key means the user sent text only, or the store write failed
 * (see [screenshotStatus]).
 *
 * [partyId] comes from the caller's bearer token — never from the request body — so a client
 * cannot attribute feedback to another customer.
 */
data class FeedbackSubmission(
    val reference: String,
    val partyId: String,
    val screenId: String,
    val category: String,
    val comment: String,
    val platform: String?,
    val appVersion: String?,
    val osVersion: String?,
    val locale: String?,
    val theme: String?,
    val sessionId: String?,
    val screenshotKey: String?,
    val screenshotBytes: Int,
    val screenshotStatus: String,
)

/**
 * Screen-feedback telemetry publisher (ADR-0192) — the write half of the feedback pipeline
 * (app -> edge -> Kafka -> analytics-sink -> ClickHouse).
 *
 * Deliberately shaped exactly like
 * [com.openbank.customeredge.infrastructure.onboarding.OnboardingFunnelPublisher]: the same
 * `AnalyticsEnvelope` JSON (eventType / aggregateType / aggregateId / occurredAt /
 * sourceService / schemaVersion / payload) that analytics-sink already ingests generically,
 * so landing a new feedback stream in the bronze layer costs a topic and a gold view, not a
 * new consumer or a new service (ADR-0192: "no new deployable service").
 *
 * WHY the screenshot never rides along: the image is personal data in a banking app (balances,
 * names, transactions). Kafka retention, the ClickHouse bronze layer (10y TTL) and every
 * downstream reader would each become a copy we must be able to erase. Keeping the bytes in
 * one lifecycle-managed bucket and passing an opaque key makes storage limitation and
 * right-to-erasure a single-place concern.
 *
 * Best-effort like the funnel publisher, but NOT silent-by-default in the same way: feedback is
 * user-initiated, so a lost event is a lost user report, not a lost telemetry sample. The emit
 * still never throws at the caller (the customer already consented and pressed send; a broker
 * hiccup must not turn into a 5xx they cannot act on), but every failure increments
 * `feedback_emit_failures_total` so the gap is alertable rather than invisible.
 */
@ApplicationScoped
class FeedbackPublisher(
    private val objectMapper: ObjectMapper,
    @Channel("feedback-out")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 512)
    private val emitter: Emitter<Record<String, String>>,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {

    // catch-all IS the contract: the customer's submission is already accepted at this point.
    @Suppress("TooGenericExceptionCaught")
    fun emit(submission: FeedbackSubmission) {
        try {
            // Low-cardinality counter (both tags are closed allow-lists) so the product side can
            // watch feedback volume live without waiting on ClickHouse — and so a broken object
            // store shows up as a STORE_FAILED rate rather than only in a log line.
            meterRegistry.counter(
                "feedback_submissions",
                "category",
                submission.category,
                "screenshot_status",
                submission.screenshotStatus,
            ).increment()

            val payload = objectMapper.createObjectNode()
            payload.put("reference", submission.reference)
            // The party rides in the payload, not the aggregate key, for the same reason the funnel
            // stream does it: the event is keyed by the thing it is about (this one report), while
            // identity stays a queryable attribute — which is also how an erasure request finds the
            // rows and the S3 objects to delete (ADR-0192, GDPR right to erasure).
            payload.put("partyId", submission.partyId)
            payload.put("screenId", submission.screenId)
            payload.put("category", submission.category)
            payload.put("comment", submission.comment)
            submission.platform?.let { payload.put("platform", it) }
            submission.appVersion?.let { payload.put("appVersion", it) }
            // Rendering context + session correlation (ADR-0192). Absent fields stay absent
            // rather than becoming "", so the warehouse can tell "not reported" from "empty".
            submission.osVersion?.let { payload.put("osVersion", it) }
            submission.locale?.let { payload.put("locale", it) }
            submission.theme?.let { payload.put("theme", it) }
            submission.sessionId?.let { payload.put("sessionId", it) }
            submission.screenshotKey?.let { payload.put("screenshotKey", it) }
            payload.put("screenshotBytes", submission.screenshotBytes)
            payload.put("screenshotStatus", submission.screenshotStatus)

            val node = objectMapper.createObjectNode()
            // Bound to a local rather than passed straight in, because the ADR-0006
            // contract/code-agreement gate reads this source and cannot evaluate a constant: it
            // matches `eventType = "<literal>"`, and without that shape a documented message looks
            // to it like one nobody emits. EVENT_TYPE below stays as the published name.
            val eventType = "feedback.submitted"
            node.put("eventType", eventType)
            node.put("aggregateType", AGGREGATE_TYPE)
            node.put("aggregateId", submission.reference)
            node.put("sourceService", "customer-edge")
            node.put("schemaVersion", 1)
            node.put("occurredAt", Instant.now(clock).toString())
            node.set<ObjectNode>("payload", payload)

            emitter.send(Record.of(submission.reference, objectMapper.writeValueAsString(node)))
                .whenComplete { _, err ->
                    if (err != null) {
                        meterRegistry.counter(FAILURE_COUNTER).increment()
                        Log.error("feedback emit failed for ${submission.reference}: ${err.message}")
                    }
                }
        } catch (e: Exception) {
            meterRegistry.counter(FAILURE_COUNTER).increment()
            Log.error("feedback emit failed for ${submission.reference}: ${e.message}", e)
        }
    }

    companion object {
        /** The contract message name (openbank-contracts/openbank-customer-edge/asyncapi.yaml). */
        const val EVENT_TYPE = "feedback.submitted"
        const val AGGREGATE_TYPE = "SCREEN_FEEDBACK"

        // Base name has NO _total suffix — the Prometheus registry appends it, so this is scraped
        // as `feedback_emit_failures_total` (the name the edge PrometheusRule alerts on).
        private const val FAILURE_COUNTER = "feedback_emit_failures"

        /** Closed allow-list — the app's `FeedbackCategory` enum, mirrored (ADR-0192). */
        val VALID_CATEGORIES = setOf("BUG", "IDEA", "CONFUSING")
    }
}
