// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.EudiResolutionResult
import com.openbank.pid.application.port.`in`.EudiVerifyPresentationUseCase
import com.openbank.pid.application.port.`in`.ResolveIdentityCommand
import com.openbank.pid.application.port.`in`.ResolveIdentityUseCase
import com.openbank.pid.application.port.`in`.VerifyPresentationCommand
import com.openbank.pid.application.port.out.CredentialStatusPort
import com.openbank.pid.application.port.out.MdocVerifierPort
import com.openbank.pid.application.port.out.PidPresentationVerifierPort
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.domain.model.PidClaims
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped

/**
 * EUDI tier-0 entry point (ADR-0094): cryptographically verify a wallet presentation, then resolve the
 * verified PID against pid via the existing three-tier resolver. The verified PID subject identifier is
 * placed in [ResolveIdentityCommand.eudiPidSubVerified] — the ONLY producer of that field — so tier-0
 * matches deterministically. EUDI is a new front door to the single dedup authority, not a fork of it.
 */
@ApplicationScoped
class EudiVerifyPresentationService(
    private val verifier: PidPresentationVerifierPort,
    private val mdocVerifier: MdocVerifierPort,
    private val resolveIdentity: ResolveIdentityUseCase,
    private val credentialStatus: CredentialStatusPort,
) : EudiVerifyPresentationUseCase {

    override suspend fun verifyAndResolve(command: VerifyPresentationCommand): EudiResolutionResult {
        // Throws PidVerificationException (→ 422) on any failed check; a returned claims set is verified.
        val claims = verifier.verify(command.vpToken, command.nonce, command.audience)
        return resolveVerifiedClaims(claims, "SD-JWT")
    }

    override suspend fun verifyAndResolveMdoc(mdocBase64Url: String): EudiResolutionResult {
        val claims = mdocVerifier.verify(mdocBase64Url)
        return resolveVerifiedClaims(claims, "mdoc")
    }

    /** Shared tail for both credential formats: revocation gate, then the three-tier resolve. */
    private suspend fun resolveVerifiedClaims(claims: PidClaims, format: String): EudiResolutionResult {
        // Revocation: a credential we issued and later revoked must not resolve, even though its
        // signature is still valid (Token Status List, ADR-0094).
        val statusUri = claims.statusListUri
        val statusIndex = claims.statusListIndex
        if (statusUri != null && statusIndex != null && credentialStatus.isRevoked(statusUri, statusIndex)) {
            throw PidVerificationException("credential has been revoked")
        }

        val resolution = resolveIdentity.resolve(
            ResolveIdentityCommand(
                givenName = claims.givenName,
                familyName = claims.familyName,
                birthdate = claims.birthDate,
                birthplace = claims.birthPlace,
                // RČ, if selectively disclosed, feeds tier-1 too; reduced to a blind index by the resolver.
                birthNumberRaw = claims.nationalIdentifier,
                nationalities = claims.nationalities,
                eudiPidSubVerified = claims.subjectId,
            ),
        )
        Log.infof(
            "EUDI %s presentation verified (loa=%s, issuer=%s) — resolution=%s",
            format,
            claims.levelOfAssurance,
            claims.issuer,
            resolution::class.simpleName,
        )
        return EudiResolutionResult(claims = claims, resolution = resolution)
    }
}
