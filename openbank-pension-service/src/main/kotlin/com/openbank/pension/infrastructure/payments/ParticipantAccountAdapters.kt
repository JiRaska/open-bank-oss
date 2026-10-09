// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.openbank.pension.application.usecase.ParticipantAccountPort
import io.quarkus.arc.profile.IfBuildProfile
import io.quarkus.arc.profile.UnlessBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Prod: FAILS CLOSED. account-service exposes no "account id of this IBAN, if held by this party"
 * lookup pension-service may call with its own client (#12387), so no mandate is set
 * up rather than one debiting an account the participant was never shown to own.
 */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class UnavailableParticipantAccountAdapter : ParticipantAccountPort {
    private val log = Logger.getLogger(UnavailableParticipantAccountAdapter::class.java)

    override suspend fun ownAccountId(partyId: UUID, iban: String): UUID? {
        log.warn("participant account lookup is not integrated; mandate refused (fail closed)")
        return null
    }
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
