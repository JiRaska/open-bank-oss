// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.openbank.pension.application.usecase.ParticipantAccountPort
import com.openbank.pension.infrastructure.identity.AccountOwnership
import com.openbank.pension.infrastructure.identity.AccountRestClient
import com.openbank.pension.infrastructure.identity.OwnershipVerificationRequestDto
import com.openbank.pension.infrastructure.identity.readOrNull
import io.quarkus.arc.profile.IfBuildProfile
import io.quarkus.arc.profile.UnlessBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Prod: account-service's ownership projection (`account.verifyOwnership`, ADR-0335 D2, #12419).
 * The debtor account id is returned ONLY when the IBAN is an ACTIVE account of exactly this party;
 * anything else (foreign, unknown, inactive) is null, so no mandate debits an account the
 * participant was never shown to own. A provider that cannot answer is 503, never a pass.
 */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class AccountServiceParticipantAccountAdapter : ParticipantAccountPort {
    @Inject
    @RestClient
    lateinit var client: AccountRestClient

    override suspend fun ownAccountId(partyId: UUID, iban: String): UUID? = AccountOwnership.ownAccountId(
        readOrNull("account-service") { client.verifyOwnership(OwnershipVerificationRequestDto(iban, partyId)) },
    )
}

/**
 * dev/test: an account is "owned" unless its IBAN is listed in [FOREIGN]; the id is derived from
 * (party, IBAN), so it can never be chosen by the caller.
 */
@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class StubParticipantAccountAdapter : ParticipantAccountPort {
    override suspend fun ownAccountId(partyId: UUID, iban: String): UUID? =
        if (iban in FOREIGN) null else UUID.nameUUIDFromBytes("$partyId|$iban".toByteArray(StandardCharsets.UTF_8))

    companion object {
        /** A well-formed IBAN the stub treats as belonging to somebody else. */
        val FOREIGN = setOf("CZ5508000000001234567899")
    }
}
