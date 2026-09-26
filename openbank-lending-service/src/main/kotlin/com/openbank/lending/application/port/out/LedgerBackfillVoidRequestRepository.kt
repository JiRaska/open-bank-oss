// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.infrastructure.persistence.entity.LedgerBackfillVoidRequestEntity
import io.smallrye.mutiny.Uni
import java.time.OffsetDateTime
import java.util.UUID

/** Persistence of the four-eyes void of a backfill's synthetic loans (#10969). Same shape as the backfill one. */
interface LedgerBackfillVoidRequestRepository {
    fun save(entity: LedgerBackfillVoidRequestEntity): Uni<LedgerBackfillVoidRequestEntity>
    fun findById(id: UUID): Uni<LedgerBackfillVoidRequestEntity?>

    /** The [limit] most recently proposed requests, newest first — the console's request history (#10618). */
    fun listRecent(limit: Int): Uni<List<LedgerBackfillVoidRequestEntity>>

    /** A still-PROPOSED request for the same plan hash — the natural key a retried propose replays to. */
    fun findProposedByHash(planHash: String): Uni<LedgerBackfillVoidRequestEntity?>

    /**
     * Every APPROVED or EXECUTED void for the same plan hash, newest first. A void already signed off
     * or already executed must not get a second request for the same journal set.
     */
    fun findSignedOffByHash(planHash: String): Uni<List<LedgerBackfillVoidRequestEntity>>

    /** Apply a decision only if the row is still PROPOSED, in one statement. `1` = this caller won. */
    fun compareAndSetDecision(entity: LedgerBackfillVoidRequestEntity): Uni<Int>

    /**
     * Claim an APPROVED request for one execution run, in one statement: succeeds only when no other
     * run holds an unexpired lease (`executed_at` null or older than [staleBefore]). `1` = claimed.
     * Two operators pressing execute together therefore cannot post the same plan concurrently.
     */
    fun claimExecution(id: UUID, executor: String, at: OffsetDateTime, staleBefore: OffsetDateTime): Uni<Int>

    /** Record the outcome of a run. [complete] moves the row to EXECUTED; otherwise it stays APPROVED and the lease is released. */
    fun recordExecution(id: UUID, resultJson: String, complete: Boolean, at: OffsetDateTime): Uni<Int>
}
