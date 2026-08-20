// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.openbank.audit.application.port.out.AnchorPublicKeyResolver
import com.openbank.audit.application.port.out.AnchorSigner
import com.openbank.audit.domain.crypto.AnchorSignatureVerifier
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
    private val publicKeys: AnchorPublicKeyResolver,
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
     * empty (nothing to attest yet).
     *
     * **Fail closed (ADR-0031 D5).** A signing failure aborts the capture and propagates. An
     * earlier version tolerated it and stored the checkpoint unsigned "rather than losing the
     * captured head" — which put rows in an append-only, evidence-bearing table that look like
     * anchors, count like anchors and attest nothing, in exactly the situation (signer
     * unreachable) where an attacker most benefits. Losing a checkpoint is recoverable; a table
     * of checkpoints that cannot be told apart from real ones is not.
     */
    suspend fun captureAnchor(): AuditAnchor? {
        val head = auditRepo.chainHead() ?: return null
        val status = if (auditRepo.verifyChain().intact) STATUS_INTACT else STATUS_BROKEN
        val signedAt = clock.instant()
        val digest = AuditAnchor.digest(head.entryId, head.recordHash, head.count, status, signedAt)
        val signature = signer.sign(digest.toByteArray(Charsets.UTF_8))
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
        log.infof("audit anchor captured: count=%d status=%s keyId=%s", head.count, status, signer.keyId)
        return anchor
    }

    /**
     * Re-verify every stored anchor **from public key material only**. For each: the signature
     * must be valid over the recomputed digest under the public key published for its `keyId`
     * (proving the anchor row itself was not edited, and proving it without any capability to
     * forge one) AND the attested head hash must still match the live chain (proving the chain was
     * not rewritten under it).
     *
     * An anchor whose key has no public material — a legacy symmetric-key row, or a key id this
     * deployment does not know — is counted as **unverifiable**, never as verified. That is a
     * distinct outcome with its own status, not a flag shared with success.
     */
    suspend fun verifyAnchors(): AnchorVerification {
        val anchors = anchorRepo.all()
        val keyCache = mutableMapOf<String, String?>()
        var verified = 0L
        var unsigned = 0L
        var unverifiable = 0L
        var firstBroken: AnchorBreak? = null
        for (a in anchors) {
            val pem = keyCache.getOrPut(a.keyId) { publicKeys.publicKeyPem(a.keyId) }
            val signatureOk = checkSignature(a, pem)
            when (signatureOk) {
                null -> if (a.signature == null) unsigned++ else unverifiable++
                true -> Unit
                false -> Unit
            }
            val headMatches = a.lastEntryId?.let { auditRepo.recordHashOf(it) } == a.lastRecordHash
            if (signatureOk == false || !headMatches) {
                if (firstBroken == null) {
                    firstBroken = AnchorBreak(
                        lastEntryId = a.lastEntryId,
                        signatureInvalid = signatureOk == false,
                        headHashMismatch = !headMatches,
                    )
                }
            } else if (signatureOk == true) {
                verified++
            }
        }
        return AnchorVerification(
            status = when {
                firstBroken != null -> STATUS_BROKEN
                unsigned + unverifiable > 0 -> STATUS_UNVERIFIABLE
                else -> STATUS_INTACT
            },
            anchorCount = anchors.size.toLong(),
            verifiedCount = verified,
            unsignedCount = unsigned,
            unverifiableCount = unverifiable,
            firstBroken = firstBroken,
        )
    }

    /**
     * Verifies one anchor's signature from public material. Returns null for "could not check at
     * all" — an unsigned legacy row, or a key with no published public key — which the caller
     * counts separately. Null is never folded into true.
     */
    private fun checkSignature(a: AuditAnchor, publicKeyPem: String?): Boolean? {
        val signature = a.signature ?: return null
        val pem = publicKeyPem ?: return null
        val digest = AuditAnchor.digest(
            a.lastEntryId,
            a.lastRecordHash,
            a.chainedCount,
            a.chainStatus,
            a.signedAt,
        ).toByteArray(Charsets.UTF_8)
        return AnchorSignatureVerifier.verify(digest, signature, pem)
    }

    /**
     * Public material for the current signing key, so a third party can verify anchors offline
     * (`cosign verify-blob --key <pem>`, or [AnchorSignatureVerifier]) without any access to the
     * signer. Returns null [AnchorKeyMaterial.publicKeyPem] when the key cannot be published —
     * reported to the caller as unavailable, never as an empty success.
     */
    suspend fun signingKeyMaterial(): AnchorKeyMaterial =
        AnchorKeyMaterial(keyId = signer.keyId, publicKeyPem = publicKeys.publicKeyPem(signer.keyId))

    suspend fun recent(limit: Int): List<AuditAnchor> = anchorRepo.recent(limit.coerceIn(1, MAX_ANCHOR_PAGE))

    private companion object {
        const val WORKFLOW_NAME = "audit-anchor-capture"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
        const val STATUS_INTACT = "INTACT"
        const val STATUS_BROKEN = "BROKEN"
        const val STATUS_UNVERIFIABLE = "UNVERIFIABLE"
        const val MAX_ANCHOR_PAGE = 200
    }
}

/** Result of re-verifying all stored anchors (see [AuditAnchorService.verifyAnchors]). */
data class AnchorVerification(
    val status: String,
    val anchorCount: Long,
    val verifiedCount: Long,
    /**
     * Legacy anchors stored without a signature at all. Capture is now fail-closed, so this can
     * only be non-zero for rows written before that change.
     */
    val unsignedCount: Long,
    /**
     * Anchors that are signed but whose key has no published public material in this deployment —
     * a legacy symmetric key, or an unknown key id. Reported as its own state: an anchor nobody
     * can check is not an anchor that passed.
     */
    val unverifiableCount: Long,
    val firstBroken: AnchorBreak?,
)

/** Public key material for an anchor signing key (ADR-0031 D5, criterion: public-only verification). */
data class AnchorKeyMaterial(val keyId: String, val publicKeyPem: String?)

/** The first anchor whose signature or attested head failed verification. */
data class AnchorBreak(val lastEntryId: UUID?, val signatureInvalid: Boolean, val headHashMismatch: Boolean)
