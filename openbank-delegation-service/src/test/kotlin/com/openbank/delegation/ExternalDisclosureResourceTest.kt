// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation

import com.openbank.delegation.application.port.`in`.ExternalDisclosureUseCase
import com.openbank.delegation.application.port.out.ExternalDisclosureArtifact
import com.openbank.delegation.infrastructure.rest.ExternalDisclosureResource
import com.openbank.delegation.infrastructure.rest.dto.ExternalDisclosureLinkRequest
import com.openbank.delegation.infrastructure.rest.dto.ExternalDisclosureOtpRequest
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

class ExternalDisclosureResourceTest {
    private val disclosures = mockk<ExternalDisclosureUseCase>()
    private val idempotency = mockk<IdempotencyStore>()
    private val resource = ExternalDisclosureResource(disclosures, idempotency)
    private val id = UUID.randomUUID()

    @Test
    fun `successful OTP verification returns no content and does not disclose metadata`(): Unit = runBlocking {
        coEvery { idempotency.get(any()) } returns null
        coEvery { idempotency.save(any(), any(), any(), any()) } returns Unit
        coEvery { disclosures.verifyOtp(id, "link-secret", "123456") } returns mockk()

        val response = resource.verifyOtp(id, ExternalDisclosureOtpRequest("link-secret", "123456", "request-1"))

        assertThat(response.status).isEqualTo(204)
        assertThat(response.entity).isNull()
        coVerify(exactly = 1) { disclosures.verifyOtp(id, "link-secret", "123456") }
    }

    @Test
    fun `unavailable verification becomes the same not found response`(): Unit = runBlocking {
        coEvery { idempotency.get(any()) } returns null
        coEvery { disclosures.verifyOtp(any(), any(), any()) } throws NotFoundException("different internal detail")

        val exception = org.assertj.core.api.Assertions.catchThrowable {
            runBlocking { resource.verifyOtp(id, ExternalDisclosureOtpRequest("bad", "000000", "request-1")) }
        }

        assertThat(exception).isInstanceOf(NotFoundException::class.java)
        assertThat(exception?.message).isEqualTo("external disclosure unavailable")
    }

    @Test
    fun `content response is the sealed artifact unchanged`(): Unit = runBlocking {
        coEvery { idempotency.get(any()) } returns null
        coEvery { idempotency.save(any(), any(), any(), any()) } returns Unit
        val sealed = byteArrayOf(37, 80, 68, 70)
        coEvery { disclosures.release(id, "link-secret") } returns ExternalDisclosureArtifact("application/pdf", sealed)

        val response = resource.content(id, ExternalDisclosureLinkRequest("link-secret", "request-1"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(sealed)
        assertThat(response.mediaType.toString()).isEqualTo("application/pdf")
    }

    @Test
    fun `content replay returns cached sealed bytes without another release`(): Unit = runBlocking {
        val sealed = byteArrayOf(37, 80, 68, 70)
        coEvery { idempotency.get(any()) } returns IdempotencyRecord(
            key = "cached",
            statusCode = 200,
            responseBody = Base64.getEncoder().encodeToString(sealed),
            createdAt = OffsetDateTime.now(),
        )

        val response = resource.content(id, ExternalDisclosureLinkRequest("link-secret", "request-1"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(sealed)
        assertThat(response.getHeaderString("X-Idempotency-Replayed")).isEqualTo("true")
        coVerify(exactly = 0) { disclosures.release(any(), any()) }
    }
}
