// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.GetXbrlCsvPreflightQuery
import com.openbank.finrep.application.port.inbound.RecordAcknowledgementCommand
import com.openbank.finrep.application.port.inbound.SupervisorySubmissionUseCase
import com.openbank.finrep.application.port.inbound.XbrlCsvPreflightUseCase
import com.openbank.finrep.application.port.out.SupervisoryReturnPackage
import com.openbank.finrep.application.port.out.SupervisoryReturnTransport
import com.openbank.finrep.application.port.out.SupervisorySubmissionRepository
import com.openbank.finrep.application.port.out.TransportResult
import com.openbank.finrep.domain.model.SubmissionAcknowledgement
import com.openbank.finrep.domain.model.SubmissionAttempt
import com.openbank.finrep.domain.model.SubmissionKey
import com.openbank.finrep.domain.model.SubmissionState
import com.openbank.finrep.domain.model.SupervisorySubmission
import com.openbank.finrep.domain.model.TransportOutcome
import com.openbank.finrep.domain.model.XbrlCsvPreflightState
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Submission lifecycle for supervisory returns (ADR-0097 follow-up). Packaging is gated on the
 * XBRL-CSV preflight; the package is a MANIFEST (identity + framework/taxonomy versions, hashed),
 * not a rendered XBRL-CSV archive — no renderer exists yet, and this class never pretends one does.
 */
@ApplicationScoped
class SupervisorySubmissionService(
    private val repository: SupervisorySubmissionRepository,
    private val preflight: XbrlCsvPreflightUseCase,
    private val transport: SupervisoryReturnTransport,
    private val clock: Clock,
) : SupervisorySubmissionUseCase {

    override suspend fun open(key: SubmissionKey, actor: String): Pair<SupervisorySubmission, Boolean> {
        repository.findByKey(key)?.let { return it to false }
        val draft = SupervisorySubmission(
            id = Ids.newId(),
            key = key,
            state = SubmissionState.DRAFT,
            createdBy = actor,
            createdAt = clock.instant(),
        )
        return repository.save(draft) to true
    }

    override suspend fun pkg(id: UUID, actor: String): SupervisorySubmission {
        val current = get(id)
        if (current.state != SubmissionState.DRAFT) return current
        val result = preflight.getPreflight(GetXbrlCsvPreflightQuery(current.key.templateId, current.key.referencePeriod))
        if (result.state != XbrlCsvPreflightState.READY_FOR_RENDERING) {
            throw ResourceConflictException(
                "preflight BLOCKED: " + result.blockers.joinToString { it.code },
                code = "PREFLIGHT_BLOCKED",
            )
        }
        val hash = manifestHash(current.key, result.reportingFrameworkVersion, result.dpmVersion, result.taxonomyVersion)
        return repository.save(
            current.packaged(result.reportingFrameworkVersion, result.taxonomyVersion, hash, actor, clock.instant()),
        )
    }

    override suspend fun submit(id: UUID, actor: String): SupervisorySubmission {
        val current = get(id)
        when (current.state) {
            SubmissionState.DRAFT -> throw ResourceConflictException("package the submission before submitting it", "NOT_PACKAGED")
            SubmissionState.SUBMITTED, SubmissionState.ACKNOWLEDGED, SubmissionState.REJECTED -> return current
            SubmissionState.PACKAGED -> Unit
        }
        val pkg = SupervisoryReturnPackage(
            submissionId = current.id,
            key = current.key,
            reportingFrameworkVersion = requireNotNull(current.reportingFrameworkVersion),
            taxonomyVersion = requireNotNull(current.taxonomyVersion),
            packageHash = requireNotNull(current.packageHash),
        )
        val result = try {
            transport.transmit(pkg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TransportResult(TransportOutcome.FAILED, detail = e.javaClass.simpleName)
        }
        val now = clock.instant()
        val attempt = SubmissionAttempt(
            id = Ids.newId(),
            submissionId = current.id,
            attemptNo = repository.attempts(current.id).size + 1,
            transport = transport.name,
            outcome = result.outcome,
            transportReference = result.reference,
            detail = result.detail,
            attemptedBy = actor,
            attemptedAt = now,
        )
        return repository.saveWithAttempt(current.afterTransport(result.outcome, result.reference, now), attempt)
    }

    override suspend fun acknowledge(id: UUID, command: RecordAcknowledgementCommand, actor: String): SupervisorySubmission {
        require(command.reference.isNotBlank()) { "acknowledgement reference is required" }
        val current = get(id)
        val ack = SubmissionAcknowledgement(command.accepted, command.reference, command.message, actor, clock.instant())
        return repository.save(current.acknowledged(ack))
    }

    override suspend fun get(id: UUID): SupervisorySubmission =
        repository.findById(id) ?: throw ResourceNotFoundException("submission $id not found", "SUBMISSION_NOT_FOUND")

    override suspend fun list(templateId: String?): List<SupervisorySubmission> = repository.list(templateId)

    override suspend fun attempts(id: UUID): List<SubmissionAttempt> {
        get(id)
        return repository.attempts(id)
    }

    internal companion object {
        fun manifestHash(key: SubmissionKey, framework: String, dpm: String, taxonomy: String): String {
            val canonical = listOf(key.templateId, key.referencePeriod.toString(), key.revision.toString(), framework, dpm, taxonomy)
                .joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
