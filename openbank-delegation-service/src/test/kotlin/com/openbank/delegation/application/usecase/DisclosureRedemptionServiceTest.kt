// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DisclosureOtpSender
import com.openbank.delegation.application.port.out.DisclosureRedemptionRepository
import com.openbank.delegation.application.port.out.DisclosureRepository
import com.openbank.delegation.application.port.out.DisclosureSnapshotContentReader
import com.openbank.delegation.domain.model.RedeemableSnapshot
import com.openbank.delegation.infrastructure.security.Pbkdf2DisclosureSecretCodec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DisclosureRedemptionServiceTest {
    private val now = Instant.parse("2026-09-09T12:00:00Z")
    private val disclosures = mockk<DisclosureRepository>()
    private val redemptions = mockk<DisclosureRedemptionRepository>()
    private val otpSender = mockk<DisclosureOtpSender>()
    private val contentReader = mockk<DisclosureSnapshotContentReader>()
    private val secrets = Pbkdf2DisclosureSecretCodec()
    private val service = DisclosureRedemptionService(
        disclosures,
        redemptions,
        secrets,
        otpSender,
        contentReader,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `download verifies bytes before atomically consuming a view`(): Unit = runBlocking {
        val ticket = secrets.newOpaqueToken()
        val bytes = "%PDF-1.7 sealed".toByteArray()
        val candidate = snapshot(sha256(bytes), 1)
        coEvery { redemptions.peek(secrets.hashOpaqueToken(ticket), now) } returns candidate
        coEvery { contentReader.read(candidate.snapshotId, candidate.snapshotSha256) } returns bytes
        coEvery { redemptions.consume(secrets.hashOpaqueToken(ticket), any(), now) } returns candidate

        val result = service.download(ticket, "download-1")

        assertThat(result.content).isEqualTo(bytes)
        assertThat(result.viewNumber).isEqualTo(1)
        coVerify(exactly = 1) { redemptions.consume(secrets.hashOpaqueToken(ticket), any(), now) }
    }

    @Test
    fun `digest mismatch never consumes a view`(): Unit = runBlocking {
        val ticket = secrets.newOpaqueToken()
        val candidate = snapshot("0".repeat(64), 1)
        coEvery { redemptions.peek(secrets.hashOpaqueToken(ticket), now) } returns candidate
        coEvery { contentReader.read(candidate.snapshotId, candidate.snapshotSha256) } returns "tampered".toByteArray()

        assertThatThrownBy { runBlocking { service.download(ticket, "download-2") } }
            .isInstanceOf(DisclosureRedemptionUnavailableException::class.java)
        coVerify(exactly = 0) { redemptions.consume(any(), any(), any()) }
    }

    @Test
    fun `losing the consume race never returns prefetched bytes`(): Unit = runBlocking {
        val ticket = secrets.newOpaqueToken()
        val bytes = "%PDF-1.7 sealed".toByteArray()
        val candidate = snapshot(sha256(bytes), 1)
        coEvery { redemptions.peek(any(), now) } returns candidate
        coEvery { contentReader.read(any(), any()) } returns bytes
        coEvery { redemptions.consume(any(), any(), now) } returns null

        assertThatThrownBy { runBlocking { service.download(ticket, "download-3") } }
            .isInstanceOf(DisclosureRedemptionInvalidException::class.java)
    }

    private fun snapshot(digest: String, view: Int) = RedeemableSnapshot(
        UUID.randomUUID(),
        UUID.randomUUID(),
        digest,
        view,
        1,
    )

    private fun sha256(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
}
