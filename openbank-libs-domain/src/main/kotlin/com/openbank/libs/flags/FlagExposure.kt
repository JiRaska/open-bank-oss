// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import java.time.Instant
import java.util.UUID

/**
 * Records that a subject was *exposed* to a flag variant — the raw signal an A/B
 * experiment is built from (ADR-0067 §6). The analytics-service joins exposures
 * to downstream conversion events (on [targetingKey]) and computes statistical
 * significance per [variant] over the SCD2 / point-in-time store (ADR-0023).
 *
 * Emitting is **fire-and-forget and decoupled** from evaluation: a flag with no
 * experiment never produces exposures, so the hot path pays nothing. Where an
 * experiment is running, the service emits through [ExposurePublisher] → outbox
 * → Kafka, reusing the regulatory-grade outbox dispatch (ADR-0050) so an
 * exposure is never lost on crash.
 *
 * [targetingKey] must be the same pseudonymous key used for bucketing (no PII —
 * GDPR Art. 30); it is what makes exposures joinable to conversions.
 */
data class FlagExposure(
    val exposureId: UUID = UUID.randomUUID(),
    val flagKey: String,
    val variant: String,
    val targetingKey: String?,
    val reason: EvaluationReason,
    /** When the subject saw the variant. Defaults to construction time; [of] never passes one. */
    val timestamp: Instant = Instant.now(),
    /** Ties the exposure back to the request log line (DORA reconstruction). */
    val traceId: String? = null,
) {
    companion object {
        /** Derive an exposure from an evaluation — the typical call site. */
        fun of(eval: FlagEvaluation<*>, targetingKey: String?, traceId: String? = null): FlagExposure = FlagExposure(
            flagKey = eval.flagKey,
            variant = eval.variant ?: eval.value.toString(),
            targetingKey = targetingKey,
            reason = eval.reason,
            traceId = traceId,
        )
    }
}

/**
 * Port for emitting [FlagExposure]s — the experimentation counterpart to
 * `AuditEventPublisher` / `ApprovalEventPublisher`. A service running an
 * experiment provides a Kafka-backed implementation (`@Alternative @Priority`);
 * the [LoggingExposurePublisher] default keeps services that run no experiments
 * working with zero wiring.
 */
interface ExposurePublisher {
    suspend fun publish(exposure: FlagExposure)
}

/**
 * Default [ExposurePublisher]: logs the exposure on a dedicated category so it is
 * visible without an analytics pipeline, and so an accidentally-shipped default
 * (where a Kafka impl was expected) is grep-able. Mirrors
 * `LoggingAuditEventPublisher`.
 */
class LoggingExposurePublisher : ExposurePublisher {
    // JDK System.Logger, not org.jboss.logging.Logger: this module must stay framework-free
    // (ADR-0002/ADR-0122, #3670). Under Quarkus the JDK logger resolves through the JUL
    // bridge into the JBoss LogManager, so the category and the output format are unchanged.
    private val log: System.Logger = System.getLogger("openbank.flags.exposure")

    override suspend fun publish(exposure: FlagExposure) {
        log.log(
            System.Logger.Level.INFO,
            "flag exposure exposureId=${exposure.exposureId} at=${exposure.timestamp} " +
                "flag=${exposure.flagKey} variant=${exposure.variant} " +
                "key=${exposure.targetingKey} reason=${exposure.reason} " +
                "traceId=${exposure.traceId}",
        )
    }
}
