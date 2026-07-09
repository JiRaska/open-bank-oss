// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.persistence.entity

import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "loan_stage_projection")
class LoanStageProjectionEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "loan_id", columnDefinition = "uuid")
    var loanId: UUID = Ids.newId()

    @Column(name = "stage", length = 16)
    var stage: String = ""

    @Column(name = "days_past_due")
    var daysPastDue: Int = 0

    @Column(name = "event_timestamp")
    var eventTimestamp: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.MIN
}
