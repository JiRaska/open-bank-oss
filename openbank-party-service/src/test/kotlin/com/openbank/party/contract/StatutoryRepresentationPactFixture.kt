// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.contract

import com.openbank.party.application.port.`in`.GrantMandateCommand
import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.domain.model.MandateAuthority
import com.openbank.party.domain.model.MandateRole
import com.openbank.party.domain.model.MandateSource
import com.openbank.party.domain.model.PartyType
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

object StatutoryRepresentationPactFixture {
    const val STATE = "a company has two active linked KYB statutory mandates"
    private val company = UUID.fromString("171f78d9-a747-4730-b804-535f93d93efa")
    private val caseId = UUID.fromString("1ffb1a70-12e4-4409-8333-bb48acbd6e48")
    private val ceremonyId = UUID.fromString("db1bd1c2-8f5a-438e-b8b4-380bc78a3c42")
    private val actors = listOf(
        UUID.fromString("6eb9e3d4-94d7-4224-9ce3-f6388d992a94"),
        UUID.fromString("1acb27bc-6d9c-4974-abf5-9249d4883d86"),
    )

    suspend fun seed(parties: PartyUseCase, seedParty: suspend (UUID, PartyType, String) -> Unit) {
        seedParty(company, PartyType.COMPANY, "Statutory Pact Fixture Company")
        actors.forEach { actor ->
            seedParty(actor, PartyType.INDIVIDUAL, "Statutory Pact Fixture Representative")
            parties.grantMandate(
                GrantMandateCommand(
                    principalPartyId = company,
                    agentPartyId = actor,
                    role = MandateRole.LEGAL_REPRESENTATIVE,
                    authority = MandateAuthority.JOINT,
                    requiredSignatures = actors.size,
                    source = MandateSource.REGISTRY,
                    evidenceRef = "kyb-case:$caseId:signature:$ceremonyId",
                ),
            )
        }
    }

    fun kybIdentity(): QuarkusSecurityIdentity {
        val name = "service-account-openbank-kyb"
        val jwt = object : JsonWebToken {
            private val claims = mapOf(
                "azp" to "openbank-kyb",
                "preferred_username" to name,
                "sub" to "statutory-pact-fixture-kyb-subject",
            )

            override fun getName(): String = name
            override fun getClaimNames(): Set<String> = claims.keys

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
        }
        return QuarkusSecurityIdentity.builder().setPrincipal(jwt).addRole("ROLE_API").build()
    }
}
