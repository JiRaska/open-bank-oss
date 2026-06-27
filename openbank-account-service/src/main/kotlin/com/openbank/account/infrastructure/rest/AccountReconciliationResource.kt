// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.infrastructure.persistence.repository.AccountReconciliationRepository
import com.openbank.libs.analytics.ReconciliationSummaryContract
import com.openbank.libs.analytics.ServiceReconciliationSummary
import io.smallrye.mutiny.Uni
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Clock
import java.time.Instant

/**
 * account-service's implementation of the shared [ReconciliationSummaryContract] (ADR-0026, Phase 1 —
 * the reference service). The path, media type and — critically — the role gate are inherited from the
 * interface in `openbank-libs`, so they cannot drift or be weakened here; this class supplies only the
 * data, read off the OLTP store through [AccountReconciliationRepository] behind the service boundary.
 *
 * Reactive (`Uni`) so it runs natively on Hibernate Reactive without blocking a worker thread. Served off
 * the customer path: the analytics-sink drives it from its off-peak reconciliation cron, never the hot path.
 */
@Tag(name = "Analytics", description = "Source-side reconciliation (ADR-0026)")
class AccountReconciliationResource(private val repository: AccountReconciliationRepository, private val clock: Clock) :
    ReconciliationSummaryContract {

    @Operation(summary = "Per-aggregate max(version) and counts for warehouse reconciliation")
    override fun reconciliationSummary(since: String?): Uni<ServiceReconciliationSummary> {
        val sinceInstant = since?.let { Instant.parse(it) }
        return repository.summary(sinceInstant).map { projection ->
            ServiceReconciliationSummary(
                service = "openbank-account-service",
                generatedAt = Instant.now(clock),
                countsByType = mapOf("Account" to projection.aggregates.size.toLong()),
                aggregates = projection.aggregates,
                watermark = projection.watermark,
            )
        }
    }
}
