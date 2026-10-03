// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.openbank.sca.application.port.out.ScaDecisionEvidencePurge
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Retention for signed device-decision evidence (`sca_device_decisions`, V13).
 *
 * A decision row keeps the exact bytes the device signed, which for a payment challenge embed the
 * amount and the creditor IBAN. That evidence must outlive the challenge (it proves who authorised
 * a payment) but must not be kept forever. The default, 1826 days (five years), matches the
 * record-keeping period for transaction evidence in AMLD (Directive (EU) 2015/849, Art. 40): SCA
 * authorisation evidence is retained as long as the payment record it authorises. Override with
 * `openbank.sca.decision-retention-days` where a longer national period applies.
 *
 * Each run deletes in batches of [batchSize], oldest first, and stops at [maxBatchesPerRun] so one
 * run is bounded even after a long outage; the next run continues. Deleting is idempotent: a row
 * already gone is not counted, so overlapping or repeated runs cannot over-delete.
 */
@ApplicationScoped
class DecisionEvidenceRetention(
    private val purge: ScaDecisionEvidencePurge,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.sca.decision-retention-days", defaultValue = DEFAULT_RETENTION_DAYS)
    private val retentionDays: Long,
    @ConfigProperty(name = "openbank.sca.decision-purge.batch-size", defaultValue = DEFAULT_BATCH_SIZE)
    private val batchSize: Int,
    @ConfigProperty(name = "openbank.sca.decision-purge.max-batches-per-run", defaultValue = DEFAULT_MAX_BATCHES)
    private val maxBatchesPerRun: Int,
) {

    init {
        require(retentionDays > 0) { "openbank.sca.decision-retention-days must be positive" }
        require(batchSize > 0) { "openbank.sca.decision-purge.batch-size must be positive" }
        require(maxBatchesPerRun > 0) { "openbank.sca.decision-purge.max-batches-per-run must be positive" }
    }

    /** Rows decided strictly before this instant are past retention. */
    fun cutoff(): OffsetDateTime = OffsetDateTime.now(clock).minusDays(retentionDays)

    /** Delete out-of-retention evidence; returns the number of rows deleted in this run. */
    suspend fun purgeExpired(): Int {
        val cutoff = cutoff()
        var total = 0
        repeat(maxBatchesPerRun) {
            val deleted = purge.purgeDecidedBefore(cutoff, batchSize)
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
