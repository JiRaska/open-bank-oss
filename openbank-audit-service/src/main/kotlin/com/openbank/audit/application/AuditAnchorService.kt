// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.openbank.audit.application.port.out.AnchorSigner
import com.openbank.audit.domain.model.AuditAnchor
import com.openbank.audit.infrastructure.persistence.AuditAnchorRepository
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Captures and verifies signed checkpoints over the audit hash chain (ADR-0031 D5).
 *
 * Periodically records the current chain head, signs it with an external key, and stores the
 * resulting [AuditAnchor]. Verification re-checks every anchor's signature AND confirms the
 * attested head still matches the live chain — catching a wholesale rewrite that an
 * internal-consistency walk ([AuditRepository.verifyChain]) alone cannot.
 */
@ApplicationScoped
class AuditAnchorService(
    private val auditRepo: AuditRepository,
    private val anchorRepo: AuditAnchorRepository,
    private val signer: AnchorSigner,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.audit.anchor.enabled", defaultValue = "true")
    private val enabled: Boolean,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(AuditAnchorService::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    /** Registers the boot-seeded ADR-0237 heartbeat before the first anchor capture. */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "\${openbank.audit.anchor.interval:1h}",
        delayed = "\${openbank.audit.anchor.initial-delay:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "audit-anchor-capture",
    )
    suspend fun captureScheduled() {
        if (!enabled) return
        runCatching { captureAnchor() }
            .onSuccess { liveness?.recordSuccess() }
            .onFailure { log.error("audit anchor capture failed", it) }
    }

    /**
     * Capture and sign a checkpoint over the current chain head. Returns null when the chain is
     * empty (nothing to attest yet). Signing failures are tolerated: an unsigned checkpoint is
     * still recorded (and reported as such by [verifyAnchors]) rather than losing the captured head.
     */
    suspend fun captureAnchor(): AuditAnchor? {
        val head = auditRepo.chainHead() ?: return null
        val status = if (auditRepo.verifyChain().intact) STATUS_INTACT else STATUS_BROKEN
        val signedAt = clock.instant()
        val digest = AuditAnchor.digest(head.entryId, head.recordHash, head.count, status, signedAt)
        val signature = runCatching { signer.sign(digest.toByteArray(Charsets.UTF_8)) }
            .onFailure { log.warn("anchor signing failed; storing unsigned checkpoint", it) }
            .getOrNull()
        val anchor = AuditAnchor(
            lastEntryId = head.entryId,
            lastRecordHash = head.recordHash,
            chainedCount = head.count,
            chainStatus = status,
            anchorDigest = digest,
            signature = signature,
            keyId = signer.keyId,
            signedAt = signedAt,
        )
        anchorRepo.save(anchor)
        log.infof("audit anchor captured: count=%d status=%s signed=%b", head.count, status, signature != null)
        return anchor
    }

    /**
     * Re-verify every stored anchor. For each: the signature must be valid over the recomputed
     * digest (proving the anchor row itself was not edited) AND the attested head hash must still
     * match the live chain (proving the chain was not rewritten under it).
     */
    suspend fun verifyAnchors(): AnchorVerification {
        val anchors = anchorRepo.all()
        var verified = 0L
        var unsigned = 0L
        var firstBroken: AnchorBreak? = null
        for (a in anchors) {
            val digest = AuditAnchor.digest(
                a.lastEntryId,
                a.lastRecordHash,
                a.chainedCount,
                a.chainStatus,
                a.signedAt,
            ).toByteArray(Charsets.UTF_8)
            val signatureOk = a.signature?.let { signer.verify(digest, it) }
            if (signatureOk == null) unsigned++
            val liveHash = a.lastEntryId?.let { auditRepo.recordHashOf(it) }
            val headMatches = liveHash == a.lastRecordHash
            if (signatureOk == false || !headMatches) {
                if (firstBroken == null) {
                    firstBroken = AnchorBreak(
                        lastEntryId = a.lastEntryId,
                        signatureInvalid = signatureOk == false,
                        headHashMismatch = !headMatches,
                    )
                }
                continue
            }
            if (signatureOk == true) verified++
        }
        return AnchorVerification(
            status = if (firstBroken == null) STATUS_INTACT else STATUS_BROKEN,
            anchorCount = anchors.size.toLong(),
            verifiedCount = verified,
            unsignedCount = unsigned,
            firstBroken = firstBroken,
        )
    }

    suspend fun recent(limit: Int): List<AuditAnchor> = anchorRepo.recent(limit.coerceIn(1, MAX_ANCHOR_PAGE))

    private companion object {
        const val WORKFLOW_NAME = "audit-anchor-capture"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
        const val STATUS_INTACT = "INTACT"
        const val STATUS_BROKEN = "BROKEN"
        const val MAX_ANCHOR_PAGE = 200
    }
}

/** Result of re-verifying all stored anchors (see [AuditAnchorService.verifyAnchors]). */
data class AnchorVerification(
    val status: String,
    val anchorCount: Long,
    val verifiedCount: Long,
    /** Anchors stored without a signature (signer was unavailable at capture time). */
    val unsignedCount: Long,
    val firstBroken: AnchorBreak?,
)

/** The first anchor whose signature or attested head failed verification. */
data class AnchorBreak(val lastEntryId: UUID?, val signatureInvalid: Boolean, val headHashMismatch: Boolean)
