// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
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

    @Column(name = "required_signer_roles", nullable = false, columnDefinition = "TEXT")
    lateinit var requiredSignerRoles: String

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

    @Column(name = "questionnaire_json", columnDefinition = "TEXT")
    var questionnaireJson: String? = null

    @Column(name = "declarations_json", columnDefinition = "TEXT")
    var declarationsJson: String? = null

    @Column(name = "agreement_json", columnDefinition = "TEXT")
    var agreementJson: String? = null

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

/**
 * One human confirmation of a representation rule (#9711). Superseded rows are KEPT — the history
 * of who confirmed what, and when the rule changed, is the audit trail this control is for.
 */
@Entity
@Table(name = "kyb_representation_attestations")
class RepresentationAttestationEntity : PanacheEntity() {
    @Column(name = "attestation_id", nullable = false, unique = true)
    lateinit var attestationId: UUID

    @Column(name = "identifier_scheme", nullable = false)
    lateinit var identifierScheme: String

    @Column(name = "identifier_value", nullable = false)
    lateinit var identifierValue: String

    @Column(name = "rule_text_hash", nullable = false)
    lateinit var ruleTextHash: String

    @Column(name = "rule_text", columnDefinition = "TEXT")
    var ruleText: String? = null

    @Column(name = "parsed_mode", nullable = false)
    lateinit var parsedMode: String

    @Column(name = "parsed_signers")
    var parsedSigners: Int? = null

    @Column(name = "confirmed_signers", nullable = false)
    var confirmedSigners: Int = 0

    @Column(name = "confirmed_roles", nullable = false, columnDefinition = "TEXT")
    lateinit var confirmedRoles: String

    @Column(name = "attested_by", nullable = false)
    lateinit var attestedBy: String

    @Column(name = "attested_at", nullable = false)
    lateinit var attestedAt: Instant

    @Column(name = "superseded_at")
    var supersededAt: Instant? = null

    @Column(name = "note", columnDefinition = "TEXT")
    var note: String? = null
}

@Entity
@Table(name = "kyb_outbox")
class KybOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}

/** Immutable, case-scoped mapped register finding; no owner details leave KYB through Kafka. */
@Entity
@Table(name = "kyb_ubo_observations")
class UboObservationEntity {
    @Id
    @Column(name = "observation_id", nullable = false)
    lateinit var observationId: UUID

    @Column(name = "case_id", nullable = false)
    lateinit var caseId: UUID

    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    @Column(name = "source", nullable = false)
    lateinit var source: String

    @Column(name = "source_sha256", nullable = false)
    lateinit var sourceSha256: String

    @Column(name = "finding_json", nullable = false, columnDefinition = "TEXT")
    lateinit var findingJson: String

    @Column(name = "fetched_at", nullable = false)
    lateinit var fetchedAt: Instant

    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant
}

/** Permanent read restriction; the original observation and its source hash remain evidentiary. */
@Entity
@Table(name = "kyb_ubo_observation_restrictions")
class UboObservationRestrictionEntity {
    @Id
    @Column(name = "observation_id", nullable = false)
    lateinit var observationId: UUID

    @Column(name = "case_id", nullable = false)
    lateinit var caseId: UUID

    @Column(name = "reason_code", nullable = false)
    lateinit var reasonCode: String

    @Column(name = "actor_id", nullable = false)
    lateinit var actorId: String

    @Column(name = "restricted_at", nullable = false)
    lateinit var restrictedAt: Instant
}

/** Append-only evidence that a staff principal read a case-scoped ownership observation. */
@Entity
@Table(name = "kyb_ubo_observation_reads")
class UboObservationReadEntity {
    @Id
    @Column(name = "read_id", nullable = false)
    lateinit var readId: UUID

    @Column(name = "case_id", nullable = false)
    lateinit var caseId: UUID

    @Column(name = "observation_id", nullable = false)
    lateinit var observationId: UUID

    @Column(name = "principal_id", nullable = false)
    lateinit var principalId: String

    @Column(name = "purpose", nullable = false)
    lateinit var purpose: String

    @Column(name = "read_at", nullable = false)
    lateinit var readAt: Instant
}
