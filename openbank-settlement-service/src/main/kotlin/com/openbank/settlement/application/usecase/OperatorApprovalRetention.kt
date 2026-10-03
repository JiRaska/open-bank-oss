// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.usecase

import com.openbank.settlement.application.port.out.SettlementOperatorApprovalPurge
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Retention for settlement operator maker/checker approvals (`settlement_operator_approvals`, V6) —
 * the same design as sca-service's operator-approval retention (#10041 slice 9b).
 *
 * An approval row records who asked for, who decided and who claimed a four-eyes-gated settlement
 * origination (`settlement.create`): it is part of the authorisation
 * evidence for the operations it gated, so it is kept as long as that evidence, 1826 days (five
 * years, AMLD Directive (EU) 2015/849 Art. 40), counted from the moment the authorization expired.
 * Override with `openbank.settlement.approval-retention-days` where a longer national period applies.
 *
 * A row is deletable once its authorization has expired and the retention period has passed since:
 * that covers decided and claimed rows and an expired PENDING row (which can never be decided or
 * claimed). A still-live approval is never deleted — the cutoff is always in the past.
 * Each run deletes in batches of [batchSize], oldest first, and stops at [maxBatchesPerRun].
 */
@ApplicationScoped
class OperatorApprovalRetention(
    private val purge: SettlementOperatorApprovalPurge,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.settlement.approval-retention-days", defaultValue = DEFAULT_RETENTION_DAYS)
    private val retentionDays: Long,
    @ConfigProperty(name = "openbank.settlement.approval-purge.batch-size", defaultValue = DEFAULT_BATCH_SIZE)
    private val batchSize: Int,
    @ConfigProperty(name = "openbank.settlement.approval-purge.max-batches-per-run", defaultValue = DEFAULT_MAX_BATCHES)
    private val maxBatchesPerRun: Int,
) {

    init {
        require(retentionDays > 0) { "openbank.settlement.approval-retention-days must be positive" }
        require(batchSize > 0) { "openbank.settlement.approval-purge.batch-size must be positive" }
        require(maxBatchesPerRun > 0) { "openbank.settlement.approval-purge.max-batches-per-run must be positive" }
    }

    /** Approvals whose authorization expired strictly before this instant are past retention. */
    fun cutoff(): OffsetDateTime = OffsetDateTime.now(clock).minusDays(retentionDays)

    /** Delete out-of-retention approvals; returns the number of rows deleted in this run. */
    suspend fun purgeExpired(): Int {
        val cutoff = cutoff()
        var total = 0
        repeat(maxBatchesPerRun) {
            val deleted = purge.purgeTerminalExpiredBefore(cutoff, batchSize)
            total += deleted
            if (deleted < batchSize) return total
        }
        return total
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = "1826"
        const val DEFAULT_BATCH_SIZE = "500"
        const val DEFAULT_MAX_BATCHES = "100"
    }
}
