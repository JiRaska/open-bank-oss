// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.persistence

import com.openbank.finrep.application.port.out.SupervisorySubmissionRepository
import com.openbank.finrep.domain.model.SubmissionAcknowledgement
import com.openbank.finrep.domain.model.SubmissionAttempt
import com.openbank.finrep.domain.model.SubmissionKey
import com.openbank.finrep.domain.model.SubmissionState
import com.openbank.finrep.domain.model.SupervisorySubmission
import com.openbank.finrep.domain.model.TransportOutcome
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "finrep_submission")
class SubmissionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = UUID(0L, 0L)

    @Column(name = "template_id", nullable = false)
    var templateId: String = ""

    @Column(name = "reference_period", nullable = false)
    var referencePeriod: LocalDate = LocalDate.MIN

    @Column(name = "revision", nullable = false)
    var revision: Int = 0

    @Column(name = "state", nullable = false)
    var state: String = ""

    @Column(name = "created_by", nullable = false)
    var createdBy: String = ""

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "reporting_framework_version")
    var reportingFrameworkVersion: String? = null

    @Column(name = "taxonomy_version")
    var taxonomyVersion: String? = null

    @Column(name = "package_hash")
    var packageHash: String? = null

    @Column(name = "packaged_by")
    var packagedBy: String? = null

    @Column(name = "packaged_at")
    var packagedAt: Instant? = null

    @Column(name = "submitted_at")
    var submittedAt: Instant? = null

    @Column(name = "transport_reference")
    var transportReference: String? = null

    @Column(name = "ack_accepted")
    var ackAccepted: Boolean? = null

    @Column(name = "ack_reference")
    var ackReference: String? = null

    @Column(name = "ack_message")
    var ackMessage: String? = null

    @Column(name = "ack_recorded_by")
    var ackRecordedBy: String? = null

    @Column(name = "ack_recorded_at")
    var ackRecordedAt: Instant? = null
}

@Entity
@Table(name = "finrep_submission_attempt")
class SubmissionAttemptEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = UUID(0L, 0L)

    @Column(name = "submission_id", nullable = false)
    var submissionId: UUID = UUID(0L, 0L)

    @Column(name = "attempt_no", nullable = false)
    var attemptNo: Int = 0

    @Column(name = "transport", nullable = false)
    var transport: String = ""

    @Column(name = "outcome", nullable = false)
    var outcome: String = ""

    @Column(name = "transport_reference")
    var transportReference: String? = null

    @Column(name = "detail")
    var detail: String? = null

    @Column(name = "attempted_by", nullable = false)
    var attemptedBy: String = ""

    @Column(name = "attempted_at", nullable = false)
    var attemptedAt: Instant? = null
}

@ApplicationScoped
class SubmissionAttemptPanacheRepository : PanacheRepositoryBase<SubmissionAttemptEntity, UUID>

@ApplicationScoped
class SubmissionPanacheRepository : PanacheRepositoryBase<SubmissionEntity, UUID>

/**
 * Both ids are application-assigned, so every write is a `merge` (upsert), never `persist` —
 * `persist` on an assigned id is INSERT-only and fails every state transition (CLAUDE.md, #1521).
 */
@ApplicationScoped
class PanacheSupervisorySubmissionRepository(
    private val submissions: SubmissionPanacheRepository,
    private val attemptRepository: SubmissionAttemptPanacheRepository,
) : SupervisorySubmissionRepository {

    override suspend fun findById(id: UUID): SupervisorySubmission? =
        Panache.withSession { submissions.findById(id) }.awaitSuspending()?.toDomain()

    override suspend fun findByKey(key: SubmissionKey): SupervisorySubmission? = Panache.withSession {
        submissions.find(
            "templateId = ?1 and referencePeriod = ?2 and revision = ?3",
            key.templateId,
            key.referencePeriod,
            key.revision,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun list(templateId: String?): List<SupervisorySubmission> = Panache.withSession {
        if (templateId == null) {
            submissions.find("order by referencePeriod desc, templateId, revision desc").list()
        } else {
            submissions.find("templateId = ?1 order by referencePeriod desc, revision desc", templateId).list()
        }
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(submission: SupervisorySubmission): SupervisorySubmission = Panache.withTransaction {
        Panache.getSession().flatMap { it.merge(submission.toEntity()) }
    }.awaitSuspending().toDomain()

    override suspend fun saveWithAttempt(
        submission: SupervisorySubmission,
        attempt: SubmissionAttempt,
    ): SupervisorySubmission = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.merge(attempt.toEntity()).flatMap { session.merge(submission.toEntity()) }
        }
    }.awaitSuspending().toDomain()

    override suspend fun attempts(submissionId: UUID): List<SubmissionAttempt> = Panache.withSession {
        attemptRepository.find("submissionId = ?1 order by attemptNo", submissionId).list()
    }.awaitSuspending().map { it.toDomain() }
}

private fun SupervisorySubmission.toEntity() = SubmissionEntity().also {
    it.id = id
    it.templateId = key.templateId
    it.referencePeriod = key.referencePeriod
    it.revision = key.revision
    it.state = state.name
    it.createdBy = createdBy
    it.createdAt = createdAt
    it.reportingFrameworkVersion = reportingFrameworkVersion
    it.taxonomyVersion = taxonomyVersion
    it.packageHash = packageHash
    it.packagedBy = packagedBy
    it.packagedAt = packagedAt
    it.submittedAt = submittedAt
    it.transportReference = transportReference
    it.ackAccepted = acknowledgement?.accepted
    it.ackReference = acknowledgement?.reference
    it.ackMessage = acknowledgement?.message
    it.ackRecordedBy = acknowledgement?.recordedBy
    it.ackRecordedAt = acknowledgement?.recordedAt
}

private fun SubmissionEntity.toDomain() = SupervisorySubmission(
    id = id,
    key = SubmissionKey(templateId, referencePeriod, revision),
    state = SubmissionState.valueOf(state),
    createdBy = createdBy,
    createdAt = requireNotNull(createdAt),
    reportingFrameworkVersion = reportingFrameworkVersion,
    taxonomyVersion = taxonomyVersion,
    packageHash = packageHash,
    packagedBy = packagedBy,
    packagedAt = packagedAt,
    submittedAt = submittedAt,
    transportReference = transportReference,
    acknowledgement = ackReference?.let {
        SubmissionAcknowledgement(
            accepted = requireNotNull(ackAccepted),
            reference = it,
            message = ackMessage,
            recordedBy = requireNotNull(ackRecordedBy),
            recordedAt = requireNotNull(ackRecordedAt),
        )
    },
)

private fun SubmissionAttempt.toEntity() = SubmissionAttemptEntity().also {
    it.id = id
    it.submissionId = submissionId
    it.attemptNo = attemptNo
    it.transport = transport
    it.outcome = outcome.name
    it.transportReference = transportReference
    it.detail = detail
    it.attemptedBy = attemptedBy
    it.attemptedAt = attemptedAt
}

private fun SubmissionAttemptEntity.toDomain() = SubmissionAttempt(
    id = id,
    submissionId = submissionId,
    attemptNo = attemptNo,
    transport = transport,
    outcome = TransportOutcome.valueOf(outcome),
    transportReference = transportReference,
    detail = detail,
    attemptedBy = attemptedBy,
    attemptedAt = requireNotNull(attemptedAt),
)
