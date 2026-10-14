// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.reporting

import com.openbank.pension.application.usecase.ParticipantSnapshotService
import com.openbank.pension.infrastructure.reporting.CaptureParticipantSnapshotRequest
import com.openbank.pension.infrastructure.reporting.ParticipantSnapshotResource
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.ForbiddenException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ParticipantSnapshotCallerGuardTest {
    @Test
    fun `non-string trusted identity claims refuse before accessing snapshot service`() {
        listOf("azp", "sub", "iss", "preferred_username").forEach { malformed ->
            val claims = mapOf<String, Any>(
                "azp" to "openbank-tax-reporting",
                "sub" to "tax-client-subject",
                "iss" to "https://issuer.example.invalid",
                "preferred_username" to "service-account-openbank-tax-reporting",
            ) + (malformed to listOf("untrusted"))
            val token = mockk<JsonWebToken> {
                every { getClaim<Any?>(any<String>()) } answers { claims[firstArg<String>()] }
                every { subject } throws AssertionError("typed subject accessor must not be used")
                every { issuer } throws AssertionError("typed issuer accessor must not be used")
            }
            val source = mockk<ParticipantSnapshotService>()
            val resource = ParticipantSnapshotResource().apply {
                identity = mockk {
                    every { isAnonymous } returns false
                    every { principal } returns token
                    every { hasRole(any()) } returns true
                }
                snapshots = source
            }
            val command = CaptureParticipantSnapshotRequest(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                LocalDate.parse("2002-03-01"),
                LocalDate.parse("2002-03-31"),
            )
            assertThatThrownBy {
                runBlocking { resource.capture("snapshot-malformed-claim-key", command) }
            }.isInstanceOf(ForbiddenException::class.java)
            coVerify(exactly = 0) { source.capture(any(), any(), any(), any(), any()) }
        }
    }
}
