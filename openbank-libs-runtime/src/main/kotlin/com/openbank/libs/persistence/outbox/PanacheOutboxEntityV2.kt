// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.Instant

/**
 * [PanacheOutboxEntity] plus the two ADR-0327 D1 columns, `claimed_at` and `next_attempt_at`.
 *
 * **Why a second mapped superclass and not two more properties on [PanacheOutboxEntity].**
 * 31 service entities already declare `claimedAt` themselves (ledger's `LedgerOutboxEntity` is the
 * reference), and Kotlin has no silent shadowing: a property on the base — `open` or not — makes
 * every one of those subclasses fail with `'claimedAt' hides member of supertype
 * 'PanacheOutboxEntity' and needs an 'override' modifier` (measured on ledger, 2026-09-30, while
 * building this class). Phase 1 of the ADR is additive and touches no service, so the base cannot
 * grow the property; and `next_attempt_at` exists on no table yet, so mapping it on the base would
 * make Hibernate ask every service's `SELECT` for a column its migration has not created (42703
 * on every outbox read, fleet-wide).
 *
 * So the columns live here, and a service adopts them in its own Phase 2/3 PR: its migration adds
 * the columns (`openbank-libs-runtime/src/main/resources/db/outbox-v2-template.sql`), its entity
 * switches `: PanacheOutboxEntity()` to `: PanacheOutboxEntityV2()` and deletes its own `claimedAt`,
 * and its repository collapses onto [AbstractPanacheOutboxRepository]. Until then the v1 base and
 * this one coexist, and [AbstractPanacheOutboxRepository] works with either — every column it
 * touches it touches through native SQL, never through a mapped property.
 */
@MappedSuperclass
open class PanacheOutboxEntityV2 : PanacheOutboxEntity() {
    /** When the row moved to DISPATCHING; read by the claim to decide whether a claim is stale. */
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null

    /** Earliest instant a FAILED row may be re-claimed (ADR-0327 D4); `null` = eligible now. */
    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null

    override fun toEntry(): OutboxEntry = super.toEntry().copy(claimedAt = claimedAt, nextAttemptAt = nextAttemptAt)
}
