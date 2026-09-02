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
    @ConfigProperty(name = "openbank.audit.anchor.signing-required", defaultValue = "false")
    private val signingRequired: Boolean,
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
        // observed-by: this job's own ADR-0237 liveness gauge. `recordSuccess()` is on the SUCCESS
        // path only, so a permanently failing capture leaves the last-success age climbing until
        // WorkflowLivenessStale fires — the failure is visible, just not through a DLQ. Swallowing
        // is also the right call for the tick itself: this is an hourly @Scheduled sweep over the
        // chain head, not a consumed message, so nothing is lost by one failed tick — the next hour
        // re-reads the same head. Throwing would only kill the scheduler thread.
        runCatching { captureAnchor() }
            .onSuccess { liveness?.recordSuccess() }
            .onFailure { log.error("audit anchor capture failed", it) }
    }

    /**
     * Capture and sign a checkpoint over the current chain head. Returns null when the chain is
     * empty (nothing to attest yet). Development HMAC mode can retain an unsigned checkpoint for
     * diagnostics; required KMS mode aborts capture rather than recording a false attestation.
     */
    suspend fun captureAnchor(): AuditAnchor? {
        val head = auditRepo.chainHead() ?: return null
        val status = if (auditRepo.verifyChain().intact) STATUS_INTACT else STATUS_BROKEN
        val signedAt = clock.instant()
        val digest = AuditAnchor.digest(head.entryId, head.recordHash, head.count, status, signedAt)
        val signed = runCatching { signer.sign(digest.toByteArray(Charsets.UTF_8)) }
            .onFailure { failure ->
                if (signingRequired) {
                    log.error("anchor signing failed; required signer prevents checkpoint capture", failure)
                } else {
                    log.warn("anchor signing failed; storing unsigned checkpoint", failure)
                }
            }
            .getOrElse { failure ->
                if (signingRequired) throw failure
                null
            }
        val anchor = AuditAnchor(
            lastEntryId = head.entryId,
            lastRecordHash = head.recordHash,
            chainedCount = head.count,
            chainStatus = status,
            anchorDigest = digest,
            signature = signed?.value,
            keyId = signed?.keyId ?: signer.keyId,
            signedAt = signedAt,
        )
        anchorRepo.save(anchor)
        log.infof("audit anchor captured: count=%d status=%s signed=%b", head.count, status, signed != null)
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
        var unverifiable = 0L
        var firstBroken: AnchorBreak? = null
        for (a in anchors) {
            when (val result = verifyAnchor(a)) {
                AnchorCheckResult.UNSIGNED -> unsigned++
                AnchorCheckResult.UNVERIFIABLE -> unverifiable++
                AnchorCheckResult.VERIFIED -> verified++
                is AnchorCheckResult.Broken -> {
                    if (firstBroken == null) firstBroken = result.breakage
                }
            }
        }
        val status = when {
            anchors.isEmpty() -> STATUS_NO_ANCHORS
            firstBroken != null -> STATUS_BROKEN
            unsigned > 0 || unverifiable > 0 -> STATUS_UNVERIFIED
            else -> STATUS_INTACT
        }
        return AnchorVerification(
            status = status,
            anchorCount = anchors.size.toLong(),
            verifiedCount = verified,
            unsignedCount = unsigned,
            unverifiableCount = unverifiable,
            firstBroken = firstBroken,
        )
    }

    suspend fun recent(limit: Int): List<AuditAnchor> = anchorRepo.recent(limit.coerceIn(1, MAX_ANCHOR_PAGE))

    /** KMS is the independent key registry; only identifiers attested on anchors are exposed. */
    suspend fun verificationKey(keyId: String): AnchorVerificationKey? {
        if (!anchorRepo.hasKeyId(keyId)) return null
        return signer.verificationKeyPem(keyId)?.let {
            AnchorVerificationKey(keyId = keyId, algorithm = "ECDSA_SHA_256", publicKeyPem = it)
        }
    }

    private suspend fun verifyAnchor(anchor: AuditAnchor): AnchorCheckResult {
        val digest = AuditAnchor.digest(
            anchor.lastEntryId,
            anchor.lastRecordHash,
            anchor.chainedCount,
            anchor.chainStatus,
            anchor.signedAt,
        ).toByteArray(Charsets.UTF_8)
        val signatureOk = anchor.signature?.let { signer.verify(digest, it, anchor.keyId) }
        val headMatches = anchor.lastEntryId?.let { auditRepo.recordHashOf(it) } == anchor.lastRecordHash
        if (signatureOk == false || !headMatches) {
            return AnchorCheckResult.Broken(
                AnchorBreak(
                    lastEntryId = anchor.lastEntryId,
                    signatureInvalid = signatureOk == false,
                    headHashMismatch = !headMatches,
                ),
            )
        }
        return when (signatureOk) {
            true -> AnchorCheckResult.VERIFIED
            null -> if (anchor.signature == null) AnchorCheckResult.UNSIGNED else AnchorCheckResult.UNVERIFIABLE
            false -> error("invalid signature is handled above")
        }
    }

    private companion object {
        const val WORKFLOW_NAME = "audit-anchor-capture"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
        const val STATUS_INTACT = "INTACT"
        const val STATUS_BROKEN = "BROKEN"
        const val STATUS_UNVERIFIED = "UNVERIFIED"
        const val STATUS_NO_ANCHORS = "NO_ANCHORS"
        const val MAX_ANCHOR_PAGE = 200
    }
}

private sealed interface AnchorCheckResult {
    data object VERIFIED : AnchorCheckResult
    data object UNSIGNED : AnchorCheckResult
    data object UNVERIFIABLE : AnchorCheckResult
    data class Broken(val breakage: AnchorBreak) : AnchorCheckResult
}

/** Result of re-verifying all stored anchors (see [AuditAnchorService.verifyAnchors]). */
data class AnchorVerification(
    val status: String,
    val anchorCount: Long,
    val verifiedCount: Long,
    /** Anchors stored without a signature (signer was unavailable at capture time). */
    val unsignedCount: Long,
    /** Signed anchors whose historical key is unavailable to the current verifier. */
    val unverifiableCount: Long,
    val firstBroken: AnchorBreak?,
)

/** The first anchor whose signature or attested head failed verification. */
data class AnchorBreak(val lastEntryId: UUID?, val signatureInvalid: Boolean, val headHashMismatch: Boolean)

/** Public material needed to verify KMS-backed anchor signatures independently of the audit database. */
data class AnchorVerificationKey(val keyId: String, val algorithm: String, val publicKeyPem: String)
