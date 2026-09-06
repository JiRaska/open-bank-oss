// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.openbank.audit.application.port.out.AnchorSignature
import com.openbank.audit.application.port.out.AnchorSigner
import com.openbank.audit.domain.model.AuditAnchor
import com.openbank.audit.infrastructure.persistence.AuditAnchorRepository
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.persistence.ChainHead
import com.openbank.audit.infrastructure.persistence.ChainVerification
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The anchor VERDICT logic (ADR-0031 D5) — the half a liveness test cannot see.
 *
 * An anchor exists to catch the one rewrite the internal chain walk cannot: every row recomputed
 * consistently under a new head. So the states that matter are the ones where the anchor and the
 * live chain DISAGREE, and where a signature cannot be judged at all. Each is asserted here as a
 * distinct outcome, because folding any two together is what turns a rewrite into a green report.
 */
class AuditAnchorVerificationTest {

    private val auditRepo = mockk<AuditRepository>()
    private val anchorRepo = mockk<AuditAnchorRepository>()
    private val signer = mockk<AnchorSigner>()
    private val metrics = mockk<DomainMetrics>()
    private val now = Instant.parse("2026-08-16T05:00:00Z")

    private fun service(signingRequired: Boolean = false) = AuditAnchorService(
        auditRepo = auditRepo,
        anchorRepo = anchorRepo,
        signer = signer,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        enabled = true,
        signingRequired = signingRequired,
        domainMetrics = metrics,
    )

    private fun anchor(
        lastEntryId: UUID? = UUID.randomUUID(),
        lastRecordHash: String? = "a".repeat(64),
        signature: String? = "sig",
        keyId: String = "kms-1",
    ) = AuditAnchor(
        lastEntryId = lastEntryId,
        lastRecordHash = lastRecordHash,
        chainedCount = 7,
        chainStatus = "INTACT",
        anchorDigest = "unused",
        signature = signature,
        keyId = keyId,
        signedAt = now,
    )

    @Test
    fun `a valid signature over an unchanged head verifies`(): Unit = runBlocking {
        val a = anchor()
        coEvery { anchorRepo.all() } returns listOf(a)
        every { signer.verify(any(), "sig", "kms-1") } returns true
        coEvery { auditRepo.recordHashOf(a.lastEntryId!!) } returns a.lastRecordHash

        val result = service().verifyAnchors()

        assertThat(result.status).isEqualTo("INTACT")
        assertThat(result.verifiedCount).isEqualTo(1)
        assertThat(result.anchorCount).isEqualTo(1)
        assertThat(result.firstBroken).isNull()
    }

    @Test
    fun `a rewritten chain under a still-valid signature is BROKEN on the head, not the signature`(): Unit =
        runBlocking {
            val a = anchor()
            coEvery { anchorRepo.all() } returns listOf(a)
            every { signer.verify(any(), "sig", "kms-1") } returns true
            // The whole point of an anchor: the row's own signature is fine, the live chain moved.
            coEvery { auditRepo.recordHashOf(a.lastEntryId!!) } returns "b".repeat(64)

            val result = service().verifyAnchors()

            assertThat(result.status).isEqualTo("BROKEN")
            assertThat(result.firstBroken?.headHashMismatch).isTrue()
            assertThat(result.firstBroken?.signatureInvalid).isFalse()
            assertThat(result.firstBroken?.lastEntryId).isEqualTo(a.lastEntryId)
        }

    @Test
    fun `an edited anchor row - invalid signature - is BROKEN on the signature`(): Unit = runBlocking {
        val a = anchor()
        coEvery { anchorRepo.all() } returns listOf(a)
        every { signer.verify(any(), "sig", "kms-1") } returns false
        coEvery { auditRepo.recordHashOf(a.lastEntryId!!) } returns a.lastRecordHash

        val result = service().verifyAnchors()

        assertThat(result.status).isEqualTo("BROKEN")
        assertThat(result.firstBroken?.signatureInvalid).isTrue()
        assertThat(result.firstBroken?.headHashMismatch).isFalse()
    }

    @Test
    fun `an unsigned checkpoint is UNVERIFIED, never counted as evidence`(): Unit = runBlocking {
        val a = anchor(signature = null)
        coEvery { anchorRepo.all() } returns listOf(a)
        coEvery { auditRepo.recordHashOf(a.lastEntryId!!) } returns a.lastRecordHash

        val result = service().verifyAnchors()

        assertThat(result.status).isEqualTo("UNVERIFIED")
        assertThat(result.unsignedCount).isEqualTo(1)
        assertThat(result.verifiedCount).isZero()
    }

    @Test
    fun `the FIRST breakage is reported, and one break outranks later good anchors`(): Unit = runBlocking {
        val bad = anchor(keyId = "kms-1")
        val good = anchor(keyId = "kms-2")
        coEvery { anchorRepo.all() } returns listOf(bad, good)
        every { signer.verify(any(), "sig", "kms-1") } returns false
        every { signer.verify(any(), "sig", "kms-2") } returns true
        coEvery { auditRepo.recordHashOf(any()) } returns "a".repeat(64)

        val result = service().verifyAnchors()

        assertThat(result.status).isEqualTo("BROKEN")
        assertThat(result.anchorCount).isEqualTo(2)
        assertThat(result.verifiedCount).isEqualTo(1)
        assertThat(result.firstBroken?.lastEntryId).isEqualTo(bad.lastEntryId)
    }

    @Test
    fun `an anchor over an empty chain head is judged without querying the live chain`(): Unit = runBlocking {
        val a = anchor(lastEntryId = null, lastRecordHash = null)
        coEvery { anchorRepo.all() } returns listOf(a)
        every { signer.verify(any(), "sig", "kms-1") } returns true

        val result = service().verifyAnchors()

        assertThat(result.status).isEqualTo("INTACT")
        coVerify(exactly = 0) { auditRepo.recordHashOf(any()) }
    }

    @Test
    fun `an empty chain attests nothing rather than storing a checkpoint over no rows`(): Unit = runBlocking {
        coEvery { auditRepo.chainHead() } returns null

        assertThat(service().captureAnchor()).isNull()
        coVerify(exactly = 0) { anchorRepo.save(any()) }
    }

    @Test
    fun `a captured anchor attests the head and the live chain status it observed`(): Unit = runBlocking {
        val head = UUID.randomUUID()
        coEvery { auditRepo.chainHead() } returns ChainHead(head, "c".repeat(64), 42)
        coEvery { auditRepo.verifyChain() } returns ChainVerification(intact = false, checked = 42, unchained = 0)
        every { signer.keyId } returns "kms-1"
        every { signer.sign(any()) } returns AnchorSignature("sig-value", "kms-1-version-3")
        val saved = slot<AuditAnchor>()
        coEvery { anchorRepo.save(capture(saved)) } returns Unit

        val result = service().captureAnchor()

        assertThat(saved.captured.chainStatus).isEqualTo("BROKEN")
        assertThat(saved.captured.chainedCount).isEqualTo(42)
        assertThat(saved.captured.lastEntryId).isEqualTo(head)
        assertThat(saved.captured.signature).isEqualTo("sig-value")
        // The key the signer actually used wins over the bean's nominal keyId, or a rotated
        // KMS generation cannot be looked up at verification time.
        assertThat(saved.captured.keyId).isEqualTo("kms-1-version-3")
        assertThat(saved.captured.anchorDigest)
            .isEqualTo(AuditAnchor.digest(head, "c".repeat(64), 42, "BROKEN", now))
        assertThat(result).isEqualTo(saved.captured)
    }

    @Test
    fun `development mode keeps an unsigned checkpoint, marked unsigned and on the bean's key id`(): Unit =
        runBlocking {
            coEvery { auditRepo.chainHead() } returns ChainHead(UUID.randomUUID(), "c".repeat(64), 1)
            coEvery { auditRepo.verifyChain() } returns ChainVerification(intact = true, checked = 1, unchained = 0)
            every { signer.keyId } returns "local-hmac-sha256"
            every { signer.sign(any()) } throws IllegalStateException("no key")
            val saved = slot<AuditAnchor>()
            coEvery { anchorRepo.save(capture(saved)) } returns Unit

            service(signingRequired = false).captureAnchor()

            assertThat(saved.captured.signature).isNull()
            assertThat(saved.captured.keyId).isEqualTo("local-hmac-sha256")
        }

    @Test
    fun `recent clamps the page size in both directions`(): Unit = runBlocking {
        coEvery { anchorRepo.recent(any()) } returns emptyList()
        val service = service()

        service.recent(0)
        service.recent(10_000)

        coVerify { anchorRepo.recent(1) }
        coVerify { anchorRepo.recent(200) }
    }

    @Test
    fun `a key id no anchor was signed with is not served, even if the signer knows it`(): Unit = runBlocking {
        coEvery { anchorRepo.hasKeyId("never-used") } returns false

        assertThat(service().verificationKey("never-used")).isNull()
        // The signer must not even be consulted: only identifiers attested on anchors are exposed.
        io.mockk.verify(exactly = 0) { signer.verificationKeyPem(any()) }
    }

    @Test
    fun `an attested key with no public half - a symmetric signer - yields no key`(): Unit = runBlocking {
        coEvery { anchorRepo.hasKeyId("local-hmac-sha256") } returns true
        every { signer.verificationKeyPem("local-hmac-sha256") } returns null

        assertThat(service().verificationKey("local-hmac-sha256")).isNull()
    }

    @Test
    fun `an attested asymmetric key is served with its algorithm`(): Unit = runBlocking {
        coEvery { anchorRepo.hasKeyId("kms-1") } returns true
        every { signer.verificationKeyPem("kms-1") } returns "-----BEGIN PUBLIC KEY-----"

        val key = service().verificationKey("kms-1")

        assertThat(key?.keyId).isEqualTo("kms-1")
        assertThat(key?.algorithm).isEqualTo("ECDSA_SHA_256")
        assertThat(key?.publicKeyPem).isEqualTo("-----BEGIN PUBLIC KEY-----")
    }
}
