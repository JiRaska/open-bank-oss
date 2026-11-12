// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.lending.domain.model.DeclineReviewStatus
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "loan_decline_review")
class DeclineReviewEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "application_id", columnDefinition = "uuid")
    var applicationId: UUID = Ids.newId()

    @Column(name = "party_id", columnDefinition = "uuid")
    var partyId: UUID = Ids.newId()

    @Column(name = "requested_at")
    var requestedAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "engine_outcome", length = 16)
    var engineOutcome: String = ""

    @Column(name = "engine_reasons", columnDefinition = "text")
    var engineReasons: String? = null

    @Column(name = "policy_versions", columnDefinition = "text")
    var policyVersions: String? = null

    @Column(name = "engine_decided_at")
    var engineDecidedAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    var status: DeclineReviewStatus = DeclineReviewStatus.REQUESTED

    @Column(name = "reviewed_by", length = 128)
    var reviewedBy: String? = null

    @Column(name = "reviewed_at")
    var reviewedAt: OffsetDateTime? = null

    @Column(name = "review_reason", length = 512)
    var reviewReason: String? = null

    @Column(name = "successor_application_id", columnDefinition = "uuid")
    var successorApplicationId: UUID? = null
}
