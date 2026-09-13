// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.openbank.analytics.application.port.out.BackfillSource
import com.openbank.analytics.application.port.out.DurableBackfillUnavailableException
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.BackfillWindow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default

/**
 * Default [BackfillSource] that fails closed when no durable reader is configured.
 *
 * Keeps the service offline-buildable while making accidental recovery requests visible. Replace
 * with an outbox/export-backed `@Alternative @Priority(...)` adapter before enabling backfills in
 * production.
 */
@ApplicationScoped
@Default
class NoOpBackfillSource : BackfillSource {
    override suspend fun read(window: BackfillWindow, request: BackfillRequest): List<String> =
        throw DurableBackfillUnavailableException()
}
