// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.openbank.analytics.application.port.out.BackfillSource
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.BackfillWindow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default
import org.jboss.logging.Logger

/**
 * Default [BackfillSource] that yields nothing and logs that no durable reader is configured.
 *
 * Keeps the service offline-buildable and the backfill orchestration fully exercisable (planning,
 * chunking, dedupe, tagging, reporting) without binding to a specific source-of-record reader.
 * Replace with an outbox/export-backed `@Alternative @Priority(...)` adapter in production.
 */
@ApplicationScoped
@Default
class NoOpBackfillSource : BackfillSource {
    private val log = Logger.getLogger(NoOpBackfillSource::class.java)

    override suspend fun read(window: BackfillWindow, request: BackfillRequest): List<String> {
        log.warnf(
            "NoOpBackfillSource: no durable reader wired; window=%s..%s source=%s returns 0 events",
            window.from, window.to, request.source
        )
        return emptyList()
    }
}
