// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.entity

import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sanctions_checks")
class SanctionsCheckEntity {
    @field:Id
    var id: UUID = UUID.randomUUID()

    @field:Column(name = "idempotency_key", unique = true)
    var idempotencyKey: String = ""

    @field:Column(name = "entity_type")
    @field:Enumerated(EnumType.STRING)
    var entityType: EntityType = EntityType.INDIVIDUAL

    @field:Column(name = "name")
    var name: String = ""

    @field:Column(name = "aliases", columnDefinition = "jsonb")
    var aliasesJson: String = "[]"

    @field:Column(name = "date_of_birth")
    var dateOfBirth: String? = null

    @field:Column(name = "nationality")
    var nationality: String? = null

    @field:Column(name = "identifiers", columnDefinition = "jsonb")
    var identifiersJson: String = "{}"

    @field:Column(name = "status")
    @field:Enumerated(EnumType.STRING)
    var status: SanctionsCheckStatus = SanctionsCheckStatus.CLEAR

    @field:Column(name = "matches", columnDefinition = "jsonb")
    var matchesJson: String = "[]"

    @field:Column(name = "overall_score")
    var overallScore: Double = 0.0

    @field:Column(name = "checked_lists", columnDefinition = "jsonb")
    var checkedListsJson: String = "[]"

    @field:Column(name = "reviewed_by")
    var reviewedBy: String? = null

    @field:Column(name = "review_note")
    var reviewNote: String? = null

    @field:Column(name = "checked_at")
    var checkedAt: Instant = Instant.now()

    @field:Column(name = "reviewed_at")
    var reviewedAt: Instant? = null
}
