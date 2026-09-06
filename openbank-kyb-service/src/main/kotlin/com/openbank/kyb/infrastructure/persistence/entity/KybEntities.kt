// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "kyb_cases")
class BusinessOnboardingCaseEntity : PanacheEntity() {
    @Column(name = "case_id", nullable = false, unique = true)
    lateinit var caseId: UUID

    @Column(name = "identifier_scheme", nullable = false)
    lateinit var identifierScheme: String

    @Column(name = "identifier_value", nullable = false)
    lateinit var identifierValue: String

    @Column(name = "initiator_party_id", nullable = false)
    lateinit var initiatorPartyId: UUID

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "extract_json", columnDefinition = "TEXT")
    var extractJson: String? = null

    @Column(name = "entity_party_id")
    var entityPartyId: UUID? = null

    @Column(name = "entity_party_active", nullable = false)
    var entityPartyActive: Boolean = false

    @Column(name = "required_signatures")
    var requiredSignatures: Int? = null

    @Column(name = "signers_json", nullable = false, columnDefinition = "TEXT")
    var signersJson: String = "[]"

    /** Denormalised for the invitation-claim lookup; the source of truth stays in [signersJson]. */
    @Column(name = "invitation_tokens", columnDefinition = "TEXT")
    var invitationTokens: String? = null

    /** Denormalised for "cases I am involved in"; the source of truth stays in [signersJson]. */
    @Column(name = "signer_party_ids", columnDefinition = "TEXT")
    var signerPartyIds: String? = null

    @Column(name = "review_reason", columnDefinition = "TEXT")
    var reviewReason: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

@Entity
@Table(name = "kyb_registry_extracts")
class RegistryExtractEntity : PanacheEntity() {
    @Column(name = "identifier_scheme", nullable = false)
    lateinit var identifierScheme: String

    @Column(name = "identifier_value", nullable = false)
    lateinit var identifierValue: String

    @Column(name = "extract_json", nullable = false, columnDefinition = "TEXT")
    lateinit var extractJson: String

    @Column(name = "source", nullable = false)
    lateinit var source: String

    @Column(name = "fetched_at", nullable = false)
    lateinit var fetchedAt: Instant
}

@Entity
@Table(name = "kyb_outbox")
class KybOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
