// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.application

import com.openbank.audit.domain.model.AuditAnchor
import com.openbank.audit.infrastructure.persistence.AuditAnchorRepository
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.signing.LocalHmacAnchorSigner
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

class AuditAnchorVerificationTest {
    private val audit = mockk<AuditRepository>()
    private val anchors = mockk<AuditAnchorRepository>()
    private val signer = LocalHmacAnchorSigner("verification-test-only")
    private val service = AuditAnchorService(audit, anchors, signer, Clock.systemUTC(), true, false, mockk())

    @Test
    fun `valid signature over an intact checkpoint verifies`(): Unit = runBlocking {
        install(anchor("INTACT"))
        val result = service.verifyAnchors()
        assertThat(result.status).isEqualTo("INTACT")
        assertThat(result.verifiedCount).isEqualTo(1)
    }

    @Test
    fun `valid signature cannot turn a broken checkpoint into intact evidence`(): Unit = runBlocking {
        install(anchor("BROKEN"))
        val result = service.verifyAnchors()
        assertThat(result.status).isEqualTo("BROKEN")
        assertThat(result.verifiedCount).isZero()
        assertThat(result.firstBroken?.capturedChainNotIntact).isTrue()
        assertThat(result.firstBroken?.signatureInvalid).isFalse()
    }

    @Test
    fun `editing only the stored digest is detected despite a valid signature`(): Unit = runBlocking {
        install(anchor("INTACT").copy(anchorDigest = "0".repeat(64)))
        val result = service.verifyAnchors()
        assertThat(result.status).isEqualTo("BROKEN")
        assertThat(result.verifiedCount).isZero()
        assertThat(result.firstBroken?.anchorDigestMismatch).isTrue()
        assertThat(result.firstBroken?.signatureInvalid).isFalse()
    }

    @Test
    fun `unsigned coherent checkpoint remains unverified rather than broken`(): Unit = runBlocking {
        install(anchor("INTACT").copy(signature = null))
        val result = service.verifyAnchors()
        assertThat(result.status).isEqualTo("UNVERIFIED")
        assertThat(result.unsignedCount).isEqualTo(1)
        assertThat(result.firstBroken).isNull()
    }

    private fun anchor(status: String): AuditAnchor {
        val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val hash = "a".repeat(64)
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val digest = AuditAnchor.digest(id, hash, 1, status, at)
        val signature = signer.sign(digest.toByteArray())
        return AuditAnchor(id, hash, 1, status, digest, signature.value, signature.keyId, at)
    }

    private fun install(anchor: AuditAnchor) {
        coEvery { anchors.all() } returns listOf(anchor)
        coEvery { audit.recordHashOf(anchor.lastEntryId!!) } returns anchor.lastRecordHash
    }
}
