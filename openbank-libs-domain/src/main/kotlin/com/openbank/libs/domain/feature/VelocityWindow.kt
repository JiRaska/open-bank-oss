// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Tumbling window matching the existing `openbank-fraud-service` velocity aggregates: H1 = the
 * current clock hour, H24 = the current clock day. Bucketed on **event time** (`occurredAt`), which
 * is the ADR-0140-correct as-of source — distinct from the service's legacy wall-clock-at-ingest
 * aggregates, which stay untouched for the live rule path.
 */
enum class VelocityWindow(private val unit: ChronoUnit) {
    H1(ChronoUnit.HOURS),
    H24(ChronoUnit.DAYS),
    ;

    /** Start of the tumbling bucket containing [t]. */
    fun bucketStart(t: Instant): Instant = t.truncatedTo(unit)
}
