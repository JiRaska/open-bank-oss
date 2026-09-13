// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application.port.out

import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.BackfillWindow

/**
 * Reads raw domain-event payloads for a [BackfillWindow] from the **durable source of record**
 * (ADR-0022).
 *
 * The critical point: this is NOT Kafka. Kafka retention is finite, so a gap from an outage longer
 * than that retention cannot be replayed from the broker. The durable source is the per-service
 * transactional **outbox** (ADR-0003) — or its archive / a periodic export — which is kept for the
 * recovery horizon. For [com.openbank.libs.analytics.IngestSource.INITIAL_LOAD] the source instead
 * projects current OLTP state (one synthetic row per existing aggregate).
 *
 * Returns raw event JSON so the backfill path reuses the *exact same* mapping and PII masking as the
 * live consumer — there is one normalisation/masking implementation, never two that can drift.
 *
 * The default binding is [com.openbank.analytics.infrastructure.reconcile.NoOpBackfillSource], which
 * returns nothing and logs that no durable reader is wired. A real adapter (outbox reader per source
 * service / S3-export reader) is the documented follow-up; the orchestration around it is real today.
 */
interface BackfillSource {
    /** Raw event payloads (same shape the live topics carry) for [window], narrowed per [request]. */
    suspend fun read(window: BackfillWindow, request: BackfillRequest): List<String>
}

/**
 * Raised when a recovery request reaches a deployment without a durable replay reader.
 *
 * An empty result is not a successful backfill: it would produce a green COMPLETED report while
 * ingesting no rows and hide an unrecoverable evidence gap. Deployments must bind an outbox/export
 * adapter before enabling recovery operations.
 */
class DurableBackfillUnavailableException :
    IllegalStateException(
        "No durable analytics backfill reader is configured; bind an outbox or archive export adapter",
    )
