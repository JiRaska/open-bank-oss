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

/** One day-close participation report (ADR-0342 D1). Append-only history; never updated. */
@Entity
@Table(name = "ledger_close_participation")
class CloseParticipationEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = Ids.newId()

    @Column(name = "business_date", nullable = false)
    var businessDate: LocalDate = LocalDate.EPOCH

    @Column(name = "participant", nullable = false)
    var participant: String = ""

    @Column(name = "outcome", nullable = false)
    var outcome: String = ""

    @Column(name = "journals_posted", nullable = false)
    var journalsPosted: Int = 0

    @Column(name = "items_processed", nullable = false)
    var itemsProcessed: Int = 0

    @Column(name = "reason")
    var reason: String? = null

    @Column(name = "reported_by", nullable = false)
    var reportedBy: String = ""

    @Column(name = "reported_at", nullable = false)
    lateinit var reportedAt: Instant

    @Column(name = "late", nullable = false)
    var late: Boolean = false
}
