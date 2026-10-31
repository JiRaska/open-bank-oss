// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass

/**
 * [PanacheOutboxEntityV2] plus the nullable `trace_context` column carrying the W3C `traceparent`
 * of the request that wrote the row.
 *
 * **Why a third mapped superclass and not a property on the v1/v2 base.** A mapped column that the
 * table lacks is not "optional" to Hibernate: every SELECT names it and fails with 42703. The same
 * reason [PanacheOutboxEntityV2] exists separately from [PanacheOutboxEntity] applies here, and
 * more strongly: ~45 services extend the v1 base and none has this column. A service adopts this
 * class in its own PR, together with a migration:
 *
 * ```
 * ALTER TABLE <svc>_outbox ADD COLUMN IF NOT EXISTS trace_context TEXT;
 * -- rollback: ALTER TABLE <svc>_outbox DROP COLUMN IF EXISTS trace_context;
 * ```
 *
 * The writer sets it with [capture]; [toEntry] hands it to the dispatcher as
 * [OutboxEntry.traceparent], and [OutboxKafkaHeaders] emits it as the `traceparent` header.
 */
@MappedSuperclass
open class PanacheOutboxEntityTraced : PanacheOutboxEntityV2() {
    /** W3C traceparent captured at write time; `null` when no valid span was current. */
    @Column(name = "trace_context", columnDefinition = "TEXT")
    var traceContext: String? = null

    /** Stamp the current span onto this row. Call from the same place the row is created. */
    fun capture() {
        traceContext = TraceparentCapture.current()
    }

    override fun toEntry(): OutboxEntry = super.toEntry().copy(traceparent = traceContext)
}
