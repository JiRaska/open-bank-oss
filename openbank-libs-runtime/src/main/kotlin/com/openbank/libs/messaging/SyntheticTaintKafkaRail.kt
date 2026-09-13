// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.messaging

import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.libs.web.MDC_SYNTHETIC
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.context.Context
import org.jboss.logging.MDC

/**
 * Where the synthetic taint enters the platform over KAFKA (ADR-0252 phase 1, #4348, #8630).
 *
 * ## What was missing, precisely
 *
 * `SyntheticTaintRequestFilter` is the only thing in the fleet that sets the two rails a
 * downstream hop can read — the `synthetic` MDC key and the `openbank.synthetic` OpenTelemetry
 * baggage entry — and it is a `ContainerRequestFilter`: inbound **HTTP** only.
 * `SyntheticTaintClientFilter` says so in its own KDoc ("Both rails can only originate from the
 * inbound trust decision"). So on a Kafka consumer thread both sources are empty, every outbound
 * REST call made while handling a record propagates nothing, and no consumer can turn a record's
 * taint into the outbox row it writes. This object is the missing origin.
 *
 * ## Why it takes a header MAP and not a `Message<>`
 *
 * `OutboxKafkaHeaders` — the producing half of this exact hop — deliberately returns a plain
 * `Map` so `openbank-libs` stays free of a Kafka dependency, and the thin service-side publisher
 * converts. This is the consuming half and it keeps the same posture, which makes the two ends
 * symmetric: `OutboxKafkaHeaders.headersFor` writes the map, [withTaintFrom] reads it.
 *
 * It is not only symmetry. libs-runtime applies no Quarkus BOM, so every dependency it declares
 * resolves STANDALONE and every POM in that graph must be present in
 * `gradle/verification-metadata.xml` — a missing checksum fails the build of every consuming
 * service, not just this module (the `quarkus-jackson` note in this module's `build.gradle.kts`).
 * Adding the reactive-messaging Kafka API here to gain a `Message<>` overload would buy a nicer
 * signature at that price. Lifting `IncomingKafkaRecordMetadata.headers` into a map is four lines
 * at the call site, in a module that already has the Kafka API on its classpath.
 *
 * ## Trust: the header is honoured, and the topic ACL is what makes that sound
 *
 * The HTTP filter refuses the header unless the caller is a named trusted principal, because
 * anyone on the internet can set an HTTP header and the taint's effect is to drop activity out of
 * AML scoring and the regulatory returns — self-service evasion if believed blindly.
 *
 * A Kafka record has no authenticated principal at the consumer, so that check has no counterpart
 * here and inventing one would be theatre. What replaces it is real: the fleet's topics are
 * mTLS + `KafkaUser` ACL-gated, so the producer set of any topic is a declared list of the bank's
 * own services, and the value on the record was itself written by `OutboxKafkaHeaders` from a
 * persisted `synthetic` column that a *trusted HTTP edge* set. The trust decision still happens
 * exactly once, at the HTTP boundary; this rail transports it. A service that consumes a topic
 * with an open producer set must not use this rail.
 *
 * ## Fail-to-real, and authoritative for the message
 *
 * Parsing is [SyntheticTaint.isTainted]: only an exact case-insensitive `true` taints; absent,
 * blank, `1`, `yes` and a stray byte are all REAL. The asymmetry is argued in [SyntheticTaint].
 *
 * Unlike the client filter — which only ever ADDS, since an outbound call may legitimately carry a
 * taint this hop knows nothing about — this rail is AUTHORITATIVE for the record it wraps: an
 * untainted record actively clears both rails for the duration and restores them afterwards. A
 * consumer thread is pooled, so an inherited value is leakage rather than truth, and inheriting it
 * would mark a real customer's record as synthetic — the unbounded, silent direction.
 */
object SyntheticTaintKafkaRail {

    /**
     * Runs [block] with both rails established from a consumed record's [headers].
     *
     * Header lookup is case-insensitive (see [SyntheticTaint.isTainted]) because casing survives
     * no transport reliably.
     */
    suspend fun <T> withTaintFrom(headers: Map<String, String?>, block: suspend () -> T): T =
        withTaint(SyntheticTaint.isTainted(headers), block)

    /**
     * Runs [block] with both rails set to [tainted], restoring the previous state afterwards.
     *
     * Both rails, not one. MDC is the rail a log line and a same-thread reader see; OpenTelemetry
     * baggage is the rail that survives reactive context propagation, which MDC does not do
     * everywhere — and the write this exists to reach happens deep inside a
     * `Panache.withTransaction { ... }` chain. Setting only MDC passes a unit test and drops the
     * taint in the real chain; the measurement for this hop is recorded in
     * `DelegatedSpendReservationTaintIT`.
     */
    suspend fun <T> withTaint(tainted: Boolean, block: suspend () -> T): T {
        val previousMdc = MDC.get(MDC_SYNTHETIC)
        val builder = Baggage.current().toBuilder()
        if (tainted) {
            builder.put(SyntheticTaint.BAGGAGE_KEY, SyntheticTaint.headerValue())
        } else {
            builder.remove(SyntheticTaint.BAGGAGE_KEY)
        }
        val scope = builder.build().storeInContext(Context.current()).makeCurrent()
        if (tainted) MDC.put(MDC_SYNTHETIC, SyntheticTaint.headerValue()) else MDC.remove(MDC_SYNTHETIC)
        try {
            return block()
        } finally {
            // Symmetric with SyntheticTaintResponseFilter, and necessary for the same reason: a
            // leaked MDC entry or OTel scope marks the NEXT record on this pooled thread.
            scope.close()
            if (previousMdc != null) MDC.put(MDC_SYNTHETIC, previousMdc) else MDC.remove(MDC_SYNTHETIC)
        }
    }

    /**
     * Whether the current context is synthetic, on either rail.
     *
     * The read side of the same pair [SyntheticTaintClientFilter] consults when it decides whether
     * to stamp an outbound request — so a persistence boundary that calls this and an outbound
     * call made in the same processing agree by construction.
     */
    fun currentlyTainted(): Boolean = mdcTainted() || baggageTainted()

    /** The MDC rail alone. Separated from [baggageTainted] so a test can prove which one carried. */
    fun mdcTainted(): Boolean = SyntheticTaint.isTainted(MDC.get(MDC_SYNTHETIC) as? String)

    /** The OpenTelemetry baggage rail alone. See [mdcTainted]. */
    fun baggageTainted(): Boolean =
        SyntheticTaint.isTainted(Baggage.current().getEntryValue(SyntheticTaint.BAGGAGE_KEY))
}
