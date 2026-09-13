// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.entity

import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Accounting-day state (ADR-0207 D2); one row per business date. */
@Entity
@Table(name = "ledger_accounting_day")
class AccountingDayEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = Ids.newId()

    @Column(name = "business_date", nullable = false, unique = true)
    var businessDate: LocalDate = LocalDate.EPOCH

    @Column(name = "status", nullable = false)
    var status: String = "OPEN"

    @Column(name = "opened_at", nullable = false)
    var openedAt: Instant = Instant.now()

    @Column(name = "opened_by", nullable = false)
    var openedBy: String = ""

    @Column(name = "cutoff_at")
    var cutoffAt: Instant? = null

    @Column(name = "tied_out_at")
    var tiedOutAt: Instant? = null

    @Column(name = "locked_at")
    var lockedAt: Instant? = null

    @Column(name = "last_transition_by")
    var lastTransitionBy: String? = null

    /**
     * Optimistic-concurrency counter, incremented by the domain on each transition. Deliberately
     * NOT `@Version`: the update is issued as a conditional JPQL statement guarded on the expected
     * value, so two operators racing the same transition cannot both win. `@Version` would give
     * the same guarantee only for a read-modify-write inside one session, which the conditional
     * update does not need.
     */
    @Column(name = "version", nullable = false)
    var version: Long = 0L

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
