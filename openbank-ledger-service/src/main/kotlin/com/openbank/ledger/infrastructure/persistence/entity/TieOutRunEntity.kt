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

/** Audit trail of sub-ledger tie-out runs (ADR-0039 Phase B); one row per run. */
@Entity
@Table(name = "ledger_tieout_runs")
class TieOutRunEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = Ids.newId()

    @Column(name = "as_of", nullable = false)
    var asOf: LocalDate = LocalDate.EPOCH

    @Column(name = "run_at", nullable = false)
    var runAt: Instant = Instant.now()

    @Column(name = "status", nullable = false)
    var status: String = "OK"

    @Column(name = "accounts_checked", nullable = false)
    var accountsChecked: Int = 0

    @Column(name = "breaks", nullable = false)
    var breaks: Int = 0

    @Column(name = "errors", nullable = false)
    var errors: Int = 0
}
