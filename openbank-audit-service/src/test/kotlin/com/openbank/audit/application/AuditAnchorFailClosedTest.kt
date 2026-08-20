// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.openbank.audit.application.port.out.AnchorPublicKeyResolver
import com.openbank.audit.application.port.out.AnchorSigner
import com.openbank.audit.application.port.out.AnchorSigningException
import com.openbank.audit.domain.crypto.AnchorSignatureVerifier
import com.openbank.audit.domain.model.AuditAnchor
import com.openbank.audit.infrastructure.persistence.AuditAnchorRepository
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.persistence.ChainHead
import com.openbank.audit.infrastructure.persistence.ChainVerification
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * The fail-closed and public-only-verification behaviour of [AuditAnchorService] (issue #5838).
 *
 * The negative cases are the point. Before this change the service *tolerated* a signing failure
 * and stored the checkpoint unsigned, which put rows that look like evidence into an append-only
 * table precisely when the signer was unreachable — the same defect shape as an adapter that
 * returns `success = true` while doing nothing. These tests fail if that tolerance ever returns:
 * `signing failure stores no anchor` goes red the moment a `runCatching` reappears around
 * `signer.sign`, and the unverifiable cases go red if a missing public key is ever counted as a
 * verified one.
 */
class AuditAnchorFailClosedTest {

    private val auditRepo = mockk<AuditRepository>()
    private val anchorRepo = mockk<AuditAnchorRepository>()
    private val signer = mockk<AnchorSigner>()
    private val publicKeys = mockk<AnchorPublicKeyResolver>()
    private val metrics = mockk<DomainMetrics>(relaxed = true)
    private val now = Instant.parse("2026-08-20T09:00:00Z")
    private val entryId = UUID.fromString("9a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9")
    private val headHash = "c".repeat(64)

    private val service = AuditAnchorService(
        auditRepo = auditRepo,
        anchorRepo = anchorRepo,
        signer = signer,
        publicKeys = publicKeys,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        enabled = true,
        domainMetrics = metrics,
    )

    private fun headIsPresent() {
        coEvery { auditRepo.chainHead() } returns ChainHead(entryId, headHash, COUNT)
        coEvery { auditRepo.verifyChain(any()) } returns
            ChainVerification(intact = true, checked = COUNT, unchained = 0)
        coEvery { auditRepo.recordHashOf(entryId) } returns headHash
        coEvery { anchorRepo.save(any()) } returns Unit
    }

    // ── criterion 2: the signer fails closed ────────────────────────────────────────────────

    @Test
    fun `an unavailable key aborts the capture and stores no anchor`(): Unit = runBlocking {
        headIsPresent()
        coEvery { signer.sign(any()) } throws AnchorSigningException("no projected ServiceAccount token")
        coEvery { signer.keyId } returns KEY_ID

        assertThatThrownBy { runBlocking { service.captureAnchor() } }
            .isInstanceOf(AnchorSigningException::class.java)

        coVerify(exactly = 0) { anchorRepo.save(any()) }
    }

    @Test
    fun `an invalid key aborts the capture and stores no anchor`(): Unit = runBlocking {
        headIsPresent()
        coEvery { signer.sign(any()) } throws AnchorSigningException("OpenBao transit sign failed: HTTP 400")
        coEvery { signer.keyId } returns KEY_ID

        assertThatThrownBy { runBlocking { service.captureAnchor() } }
            .isInstanceOf(AnchorSigningException::class.java)

        coVerify(exactly = 0) { anchorRepo.save(any()) }
    }

    @Test
    fun `a working key stores a signed anchor`(): Unit = runBlocking {
        headIsPresent()
        coEvery { signer.keyId } returns KEY_ID
        coEvery { signer.sign(any()) } returns "c2lnbmF0dXJl"

        val anchor = service.captureAnchor()

        assertThat(anchor?.signature).isEqualTo("c2lnbmF0dXJl")
        assertThat(anchor?.keyId).isEqualTo(KEY_ID)
        coVerify(exactly = 1) { anchorRepo.save(any()) }
    }

    // ── criterion 1: verification uses public material, and reports what it could not check ──

    @Test
    fun `a real anchor verifies with only the public key available to the service`(): Unit = runBlocking {
        val material = signAnchorWithAThrowawayKey()
        coEvery { anchorRepo.all() } returns listOf(material.anchor)
        coEvery { auditRepo.recordHashOf(entryId) } returns headHash
        coEvery { publicKeys.publicKeyPem(KEY_ID) } returns material.publicKeyPem

        val result = service.verifyAnchors()

        assertThat(result.status).isEqualTo("INTACT")
        assertThat(result.verifiedCount).isEqualTo(1)
        assertThat(result.unverifiableCount).isZero()
    }

    @Test
    fun `an anchor whose key has no public material is UNVERIFIABLE, never verified`(): Unit = runBlocking {
        val material = signAnchorWithAThrowawayKey()
        coEvery { anchorRepo.all() } returns listOf(material.anchor)
        coEvery { auditRepo.recordHashOf(entryId) } returns headHash
        coEvery { publicKeys.publicKeyPem(KEY_ID) } returns null

        val result = service.verifyAnchors()

        assertThat(result.status).isEqualTo("UNVERIFIABLE")
        assertThat(result.verifiedCount).isZero()
        assertThat(result.unverifiableCount).isEqualTo(1)
    }

    @Test
    fun `a legacy unsigned anchor is UNVERIFIABLE, never verified`(): Unit = runBlocking {
        val material = signAnchorWithAThrowawayKey()
        coEvery { anchorRepo.all() } returns listOf(material.anchor.copy(signature = null))
        coEvery { auditRepo.recordHashOf(entryId) } returns headHash
        coEvery { publicKeys.publicKeyPem(KEY_ID) } returns material.publicKeyPem

        val result = service.verifyAnchors()

        assertThat(result.status).isEqualTo("UNVERIFIABLE")
        assertThat(result.verifiedCount).isZero()
        assertThat(result.unsignedCount).isEqualTo(1)
    }

    @Test
    fun `a tampered anchor row is BROKEN`(): Unit = runBlocking {
        val material = signAnchorWithAThrowawayKey()
        // The attacker edits the attested count; the digest changes, the signature no longer fits.
        coEvery { anchorRepo.all() } returns listOf(material.anchor.copy(chainedCount = COUNT + 1))
        coEvery { auditRepo.recordHashOf(entryId) } returns headHash
        coEvery { publicKeys.publicKeyPem(KEY_ID) } returns material.publicKeyPem

        val result = service.verifyAnchors()

        assertThat(result.status).isEqualTo("BROKEN")
        assertThat(result.firstBroken?.signatureInvalid).isTrue()
    }

    @Test
    fun `a rewritten chain under a valid anchor is BROKEN`(): Unit = runBlocking {
        val material = signAnchorWithAThrowawayKey()
        coEvery { anchorRepo.all() } returns listOf(material.anchor)
        coEvery { auditRepo.recordHashOf(entryId) } returns "d".repeat(64)
        coEvery { publicKeys.publicKeyPem(KEY_ID) } returns material.publicKeyPem

        val result = service.verifyAnchors()

        assertThat(result.status).isEqualTo("BROKEN")
        assertThat(result.firstBroken?.headHashMismatch).isTrue()
    }

    @Test
    fun `signing key material reports an unavailable public key as null, not as an empty success`(): Unit =
        runBlocking {
            coEvery { signer.keyId } returns KEY_ID
            coEvery { publicKeys.publicKeyPem(KEY_ID) } returns null

            assertThat(service.signingKeyMaterial().publicKeyPem).isNull()
        }

    /**
     * Builds a genuinely-signed anchor. The private key exists only inside this function and is
     * never returned — everything the tests above verify, they verify from [publicKeyPem] alone.
     */
    private fun signAnchorWithAThrowawayKey(): SignedFixture {
        val digest = AuditAnchor.digest(entryId, headHash, COUNT, "INTACT", now)
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val signature = Signature.getInstance(AnchorSignatureVerifier.ALGORITHM).run {
            initSign(pair.private)
            update(digest.toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(PEM_LINE_LENGTH, "\n".toByteArray()).encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"
        return SignedFixture(
            anchor = AuditAnchor(
                lastEntryId = entryId,
                lastRecordHash = headHash,
                chainedCount = COUNT,
                chainStatus = "INTACT",
                anchorDigest = digest,
                signature = signature,
                keyId = KEY_ID,
                signedAt = now,
            ),
            publicKeyPem = pem,
        )
    }

    private data class SignedFixture(val anchor: AuditAnchor, val publicKeyPem: String)

    private companion object {
        const val KEY_ID = "openbao-transit:transit/audit-anchor"
        const val COUNT = 7L
        const val PEM_LINE_LENGTH = 64
    }
}
