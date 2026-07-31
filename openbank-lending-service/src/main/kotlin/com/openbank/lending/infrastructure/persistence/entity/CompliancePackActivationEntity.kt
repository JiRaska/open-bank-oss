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

@Entity
@Table(name = "compliance_pack_activation")
class CompliancePackActivationEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "state", length = 16)
    @Enumerated(EnumType.STRING)
    var state: ProposalState = ProposalState.PROPOSED

    @Column(name = "jurisdiction", length = 8)
    var jurisdiction: String = ""

    @Column(name = "product_type", length = 32)
    var productType: String = ""

    @Column(name = "pack_version")
    var packVersion: Int = 0

    @Column(name = "effective_from")
    var effectiveFrom: LocalDate = LocalDate.EPOCH

    @Column(name = "payload", columnDefinition = "text")
    var payload: String = ""

    @Column(name = "content_hash", length = 64)
    var contentHash: String = ""

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

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}
