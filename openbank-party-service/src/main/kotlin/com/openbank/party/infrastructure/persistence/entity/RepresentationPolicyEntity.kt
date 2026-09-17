// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "party_representation_policies")
class RepresentationPolicyEntity : PanacheEntity() {
    @Column(name = "policy_id", nullable = false, unique = true)
    lateinit var policyId: UUID

    @Column(name = "principal_party_id", nullable = false)
    lateinit var principalPartyId: UUID

    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    @Column(name = "source_case_id", nullable = false, unique = true)
    lateinit var sourceCaseId: UUID

    @Column(name = "rule_text_hash", nullable = false, length = 64)
    lateinit var ruleTextHash: String

    @Column(name = "mode", nullable = false)
    lateinit var mode: String

    @Column(name = "required_signatures", nullable = false)
    var requiredSignatures: Int = 0

    @Column(name = "required_offices_json", nullable = false, columnDefinition = "TEXT")
    lateinit var requiredOfficesJson: String

    @Column(name = "eligible_representatives_json", nullable = false, columnDefinition = "TEXT")
    lateinit var eligibleRepresentativesJson: String

    @Column(name = "evidence_ref", nullable = false)
    lateinit var evidenceRef: String

    @Column(name = "effective_from", nullable = false)
    lateinit var effectiveFrom: Instant
}
