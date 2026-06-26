// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.ResolutionResult
import com.openbank.pid.application.port.`in`.ResolveIdentityUseCase
import com.openbank.pid.application.port.`in`.VerifyPresentationCommand
import com.openbank.pid.application.port.out.CredentialStatusPort
import com.openbank.pid.application.port.out.MdocVerifierPort
import com.openbank.pid.application.port.out.PidPresentationVerifierPort
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.domain.model.PidClaims
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The revocation enforcement invariant: a credential we issued and later revoked must NOT resolve,
 * even though its issuer signature is still valid (Token Status List, ADR-0094).
 */
class EudiVerifyPresentationServiceTest {

    private val verifier = mockk<PidPresentationVerifierPort>()
    private val mdoc = mockk<MdocVerifierPort>()
    private val resolver = mockk<ResolveIdentityUseCase>()
    private val status = mockk<CredentialStatusPort>()
    private val service = EudiVerifyPresentationService(verifier, mdoc, resolver, status)

    private val command = VerifyPresentationCommand(vpToken = "vc~~", nonce = null, audience = null)

    private fun claims(uri: String?, index: Long?) = PidClaims(
        subjectId = "sub:CZ-1",
        givenName = "A",
        familyName = "B",
        birthDate = LocalDate.of(1990, 1, 1),
        issuingCountry = "CZ",
        issuer = "https://pid.open-bank.tech",
        statusListUri = uri,
        statusListIndex = index,
    )

    @Test
    fun `a revoked credential is rejected even though its signature is valid`() {
        every { verifier.verify(any(), any(), any()) } returns claims("https://pid/status/1", 7)
        coEvery { status.isRevoked("https://pid/status/1", 7) } returns true

        assertThatThrownBy { runBlocking { service.verifyAndResolve(command) } }
            .isInstanceOf(PidVerificationException::class.java)
            .hasMessageContaining("revoked")
    }

    @Test
    fun `a non-revoked credential resolves normally`(): Unit = runBlocking {
        every { verifier.verify(any(), any(), any()) } returns claims("https://pid/status/1", 7)
        coEvery { status.isRevoked("https://pid/status/1", 7) } returns false
        coEvery { resolver.resolve(any()) } returns ResolutionResult.NoMatch

        val result = service.verifyAndResolve(command)
        assertThat(result.resolution).isEqualTo(ResolutionResult.NoMatch)
    }

    @Test
    fun `a credential with no status claim skips the revocation check (backward compatible)`(): Unit = runBlocking {
        every { verifier.verify(any(), any(), any()) } returns claims(uri = null, index = null)
        coEvery { resolver.resolve(any()) } returns ResolutionResult.NoMatch

        val result = service.verifyAndResolve(command)
        assertThat(result.resolution).isEqualTo(ResolutionResult.NoMatch)
        coVerify(exactly = 0) { status.isRevoked(any(), any()) }
    }
}
