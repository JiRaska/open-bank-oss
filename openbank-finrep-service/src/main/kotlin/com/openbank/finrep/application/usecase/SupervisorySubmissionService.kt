// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.GetXbrlCsvPreflightQuery
import com.openbank.finrep.application.port.inbound.ReconcileCommand
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
    // How long a SUBMITTING claim may be held before its holder is presumed dead. Must exceed the
    // transport's own timeout, or a slow but live transmission would be marked OUTCOME_UNKNOWN.
    @ConfigProperty(name = "openbank.finrep.submission.claim-lease", defaultValue = "PT15M")
    private val lease: Duration,
) : SupervisorySubmissionUseCase {

    // A concurrent open of the same revision loses the unique-key race at INSERT; whatever the
    // persistence exception type, the row that won is the answer (200), never an unmapped 500.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun open(key: SubmissionKey, actor: String): Pair<SupervisorySubmission, Boolean> {
        repository.findByKey(key)?.let { return it to false }
        val draft = SupervisorySubmission(
            id = Ids.newId(),
            key = key,
            state = SubmissionState.DRAFT,
            createdBy = actor,
            createdAt = clock.instant(),
        )
        return try {
            repository.save(draft) to true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val winner = repository.findByKey(key) ?: throw e
            winner to false
        }
    }

    override suspend fun pkg(id: UUID, actor: String): SupervisorySubmission {
        val current = get(id)
        if (current.state != SubmissionState.DRAFT) return current
        val result = preflight.getPreflight(
            GetXbrlCsvPreflightQuery(current.key.templateId, current.key.referencePeriod),
        )
        if (result.state != XbrlCsvPreflightState.READY_FOR_RENDERING) {
            throw ResourceConflictException(
                "preflight BLOCKED: " + result.blockers.joinToString { it.code },
                code = "PREFLIGHT_BLOCKED",
            )
        }
        val hash =
            manifestHash(current.key, result.reportingFrameworkVersion, result.dpmVersion, result.taxonomyVersion)
        return repository.save(
            current.packaged(result.reportingFrameworkVersion, result.taxonomyVersion, hash, actor, clock.instant()),
        )
    }

    override suspend fun submit(id: UUID, actor: String): SupervisorySubmission {
        val current = get(id)
        when (current.state) {
            SubmissionState.DRAFT -> throw ResourceConflictException(
                "package the submission before submitting it",
                "NOT_PACKAGED",
            )

            SubmissionState.SUBMITTED, SubmissionState.ACKNOWLEDGED, SubmissionState.REJECTED -> return current

            SubmissionState.OUTCOME_UNKNOWN -> throw outcomeUnknown(id)

            SubmissionState.SUBMITTING -> {
                // A dead holder's claim must not wedge the return forever, nor be resubmitted:
                // the transport may have delivered, so a lapsed claim becomes OUTCOME_UNKNOWN.
                if (current.claimExpired(clock.instant(), lease)) return lapse(current, actor)
                throw inProgress(id)
            }

            SubmissionState.PACKAGED -> Unit
        }
        // Claim BEFORE calling the transport: with a live adapter two concurrent calls would be a
        // double filing. Losing the claim means another caller is transmitting, or has finished.
        val claimedAt = clock.instant()
        if (!repository.claimForSubmission(current.id, claimedAt)) {
            val now = get(id)
            if (now.state in SETTLED) return now
            throw inProgress(id)
        }
        return transmitClaimed(current.claimed(claimedAt), actor)
    }

    /**
     * Holds the claim from here on: every path out of this function records an outcome, and the
     * recording runs NonCancellable so a client disconnect cannot leave the row SUBMITTING.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun transmitClaimed(claimed: SupervisorySubmission, actor: String): SupervisorySubmission {
        val result = try {
            transport.transmit(claimed.toPackage())
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                record(claimed, TransportResult(TransportOutcome.UNKNOWN, detail = "cancelled mid-transmission"), actor)
            }
            throw e
        } catch (e: Exception) {
            // The adapter threw: it may have delivered before failing. Never assume FAILED.
            TransportResult(TransportOutcome.UNKNOWN, detail = "transport threw ${e.javaClass.simpleName}")
        }
        return withContext(NonCancellable) { record(claimed, result, actor) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun record(
        claimed: SupervisorySubmission,
        result: TransportResult,
        actor: String,
    ): SupervisorySubmission {
        val now = clock.instant()
        val next = claimed.afterTransport(result.outcome, result.reference, now)
        val attempt = attempt(claimed, transport.name, result.outcome, result.reference, result.detail, actor, now)
        val moved = try {
            repository.transition(SubmissionState.SUBMITTING, next, attempt)
        } catch (e: Exception) {
            // One retry; if the store is down the claim stays SUBMITTING and lapses to
            // OUTCOME_UNKNOWN after the lease — never back to a resubmittable PACKAGED.
            log.warnf(e, "recording outcome %s for submission %s failed; retrying once", result.outcome, claimed.id)
            repository.transition(SubmissionState.SUBMITTING, next, attempt)
        }
        // The claim lapsed while we transmitted: a late but real answer still beats "unknown".
        if (!moved) repository.transition(SubmissionState.OUTCOME_UNKNOWN, next, attempt)
        return get(claimed.id)
    }

    private suspend fun lapse(current: SupervisorySubmission, actor: String): SupervisorySubmission {
        val now = clock.instant()
        val attempt = attempt(
            current,
            "claim-lease",
            TransportOutcome.UNKNOWN,
            null,
            "claim lease expired; delivery unknown — reconcile with the supervisor",
            actor,
            now,
        )
        repository.transition(
            SubmissionState.SUBMITTING,
            current.claimLapsed(),
            attempt,
            claimedBefore = now.minus(lease),
        )
        return get(current.id)
    }

    override suspend fun reconcile(id: UUID, command: ReconcileCommand, actor: String): SupervisorySubmission {
        if (command.sent) require(!command.reference.isNullOrBlank()) { "reference is required when sent = true" }
        var current = get(id)
        if (current.state == SubmissionState.SUBMITTING && current.claimExpired(clock.instant(), lease)) {
            current = lapse(current, actor)
        }
        if (current.state != SubmissionState.OUTCOME_UNKNOWN) {
            throw ResourceConflictException(
                "only an OUTCOME_UNKNOWN submission (or an expired claim) can be reconciled; state is ${current.state}",
                "NOT_RECONCILABLE",
            )
        }
        val now = clock.instant()
        val next = current.reconciled(command.sent, command.reference, now)
        val outcome = if (command.sent) TransportOutcome.SUBMITTED else TransportOutcome.FAILED
        val attempt = attempt(current, "operator-reconcile", outcome, command.reference, command.note, actor, now)
        if (!repository.transition(SubmissionState.OUTCOME_UNKNOWN, next, attempt)) {
            throw ResourceConflictException("submission $id changed while reconciling", "CONFLICT")
        }
        return get(id)
    }

    private suspend fun attempt(
        submission: SupervisorySubmission,
        transportName: String,
        outcome: TransportOutcome,
        reference: String?,
        detail: String?,
        actor: String,
        at: Instant,
    ) = SubmissionAttempt(
        id = Ids.newId(),
        submissionId = submission.id,
        attemptNo = repository.attempts(submission.id).size + 1,
        transport = transportName,
        outcome = outcome,
        transportReference = reference,
        detail = detail,
        attemptedBy = actor,
        attemptedAt = at,
    )

    private fun SupervisorySubmission.toPackage() = SupervisoryReturnPackage(
        submissionId = id,
        key = key,
        reportingFrameworkVersion = requireNotNull(reportingFrameworkVersion),
        taxonomyVersion = requireNotNull(taxonomyVersion),
        packageHash = requireNotNull(packageHash),
    )

    override suspend fun acknowledge(
        id: UUID,
        command: RecordAcknowledgementCommand,
        actor: String,
    ): SupervisorySubmission {
        require(command.reference.isNotBlank()) { "acknowledgement reference is required" }
        val current = get(id)
        val ack =
            SubmissionAcknowledgement(command.accepted, command.reference, command.message, actor, clock.instant())
        return repository.save(current.acknowledged(ack))
    }

    override suspend fun get(id: UUID): SupervisorySubmission =
        repository.findById(id) ?: throw ResourceNotFoundException("submission $id not found", "SUBMISSION_NOT_FOUND")

    override suspend fun list(templateId: String?): List<SupervisorySubmission> = repository.list(templateId)

    override suspend fun attempts(id: UUID): List<SubmissionAttempt> {
        get(id)
        return repository.attempts(id)
    }

    private fun outcomeUnknown(id: UUID) = ResourceConflictException(
        "submission $id may already have been delivered; an ADMIN must reconcile it before any resubmit",
        "OUTCOME_UNKNOWN",
    )

    private fun inProgress(id: UUID) =
        ResourceConflictException("submission $id is being transmitted by another request", "SUBMISSION_IN_PROGRESS")

    internal companion object {
        private val log: Logger = Logger.getLogger(SupervisorySubmissionService::class.java)
        private val SETTLED = setOf(SubmissionState.SUBMITTED, SubmissionState.ACKNOWLEDGED, SubmissionState.REJECTED)

        fun manifestHash(key: SubmissionKey, framework: String, dpm: String, taxonomy: String): String {
            val canonical = listOf(
                key.templateId,
                key.referencePeriod.toString(),
                key.revision.toString(),
                framework,
                dpm,
                taxonomy,
            )
                .joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
