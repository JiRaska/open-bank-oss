// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Four-eyes record of one ledger backfill request (#10746, V19). Every column named explicitly. */
@Entity
@Table(name = "ledger_backfill_request")
class LedgerBackfillRequestEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "state", length = 16)
    @Enumerated(EnumType.STRING)
    var state: ProposalState = ProposalState.PROPOSED

    @Column(name = "cutover_date")
    var cutoverDate: LocalDate = LocalDate.EPOCH

    @Column(name = "plan_hash", length = 64)
    var planHash: String = ""

    @Column(name = "loan_count")
    var loanCount: Int = 0

    @Column(name = "leg_count")
    var legCount: Int = 0

    @Column(name = "proposed_by", length = 128)
    var proposedBy: String = ""

    @Column(name = "proposed_at")
    var proposedAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "decided_by", length = 128)
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null

    @Column(name = "decision_reason", length = 512)
    var decisionReason: String? = null

    @Column(name = "executed_by", length = 128)
    var executedBy: String? = null

    @Column(name = "executed_at")
    var executedAt: OffsetDateTime? = null

    @Column(name = "last_result", columnDefinition = "text")
    var lastResult: String? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}
