// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.mapper

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.sanctions.domain.model.*
import com.openbank.sanctions.infrastructure.persistence.entity.SanctionsCheckEntity

private val mapper = jacksonObjectMapper()

fun SanctionsCheckEntity.toDomain() = SanctionsCheck(
    id, idempotencyKey, entityType, name,
    mapper.readValue<List<String>>(aliasesJson),
    dateOfBirth, nationality,
    mapper.readValue<Map<String, String>>(identifiersJson),
    status,
    mapper.readValue<List<SanctionsMatch>>(matchesJson),
    overallScore,
    mapper.readValue<List<SanctionsListType>>(checkedListsJson),
    reviewedBy, reviewNote, checkedAt, reviewedAt,
)

fun SanctionsCheck.toEntity() = SanctionsCheckEntity().also {
    it.id = id
    it.idempotencyKey = idempotencyKey
    it.entityType = entityType
    it.name = name
    it.aliasesJson = mapper.writeValueAsString(aliases)
    it.dateOfBirth = dateOfBirth
    it.nationality = nationality
    it.identifiersJson = mapper.writeValueAsString(identifiers)
    it.status = status
    it.matchesJson = mapper.writeValueAsString(matches)
    it.overallScore = overallScore
    it.checkedListsJson = mapper.writeValueAsString(checkedLists)
    it.reviewedBy = reviewedBy
    it.reviewNote = reviewNote
    it.checkedAt = checkedAt
    it.reviewedAt = reviewedAt
}
