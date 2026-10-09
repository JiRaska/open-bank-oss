// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.identity

import com.openbank.pension.application.exit.BeneficiaryVerificationPort
import com.openbank.pension.application.exit.ClaimantKyc
import com.openbank.pension.application.exit.OwnAccountVerificationPort
import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.onboarding.KycProfile
import com.openbank.pension.application.onboarding.PartyKycPort
import com.openbank.pension.application.onboarding.SignatureOutcome
import com.openbank.pension.application.onboarding.SignatureVerificationPort
import io.quarkus.arc.profile.UnlessBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/*
 * The REAL identity and SCA adapters (ADR-0334, #12377). Present only in a prod build
 * (`@UnlessBuildProfile`); `%dev`/`%test` keep the fail-closed stubs. Decisions live in
 * IdentityChecks.kt and are unit-tested there.
 */

/** Onboarding signature + transfer SCA -> sca-service consume (APPROVAL, single use). */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class ScaSignatureVerificationAdapter : SignatureVerificationPort {
    @Inject
    @RestClient
    lateinit var client: ScaConsumeRestClient

    private val gate by lazy { ScaConsumeGate { id, req -> client.consume(id, req) } }

    override suspend fun verify(
        partyId: UUID,
        challengeId: String,
        documentSha256: String?,
        operationRef: String,
    ): SignatureOutcome = if (gate.spend(partyId, challengeId, ScaBinding.forOperation(operationRef, documentSha256))) {
        SignatureOutcome.VERIFIED
    } else {
        SignatureOutcome.REJECTED
    }
}

/** Document-bound SCA (exit, schedule, beneficiaries, annuity, mandate) -> sca-service consume. */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class ScaDocumentVerificationAdapter : ScaVerificationPort {
    @Inject
    @RestClient
    lateinit var client: ScaConsumeRestClient

    private val gate by lazy { ScaConsumeGate { id, req -> client.consume(id, req) } }

    override suspend fun verify(
        partyId: UUID,
        challengeId: String,
        documentSha256: String,
        operation: ScaOperation,
    ): Boolean = gate.spend(partyId, challengeId, ScaBinding.forDocument(operation, documentSha256))
}

/** party-service party record (with kyc-service's mirrored verdict). Unknown party -> null. */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class PartyKycRestAdapter : PartyKycPort {
    @Inject
    @RestClient
    lateinit var client: PartyRestClient

    override suspend fun profile(partyId: UUID): KycProfile? =
        readOrNull("party-service") { client.party(partyId) }?.let(PartyKycMapping::profile)
}

/** account-service: the payout IBAN is an ACTIVE account of the participant. */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class AccountOwnershipRestAdapter : OwnAccountVerificationPort {
    @Inject
    @RestClient
    lateinit var client: AccountRestClient

    override suspend fun isOwnVerifiedAccount(partyId: UUID, iban: String): Boolean = AccountOwnership.owns(
        readOrNull("account-service") { client.verifyOwnership(OwnershipVerificationRequestDto(iban, partyId)) },
    )
}

/** account-service + party-service KYC-light check of a beneficiary. */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class BeneficiaryLightKycRestAdapter : BeneficiaryVerificationPort {
    @Inject
    @RestClient
    lateinit var accounts: AccountRestClient

    @Inject
    @RestClient
    lateinit var parties: PartyRestClient

    private val check by lazy {
        BeneficiaryLightKyc(
            { iban, party ->
                readOrNull("account-service") { accounts.verifyOwnership(OwnershipVerificationRequestDto(iban, party)) }
            },
            { id -> readOrNull("party-service") { parties.party(id) } },
        )
    }

    override suspend fun verify(kyc: ClaimantKyc): Boolean = check.verify(kyc)
}
