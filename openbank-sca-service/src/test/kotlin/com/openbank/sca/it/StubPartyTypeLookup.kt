// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.it

import com.openbank.sca.application.port.out.PartyTypeLookup
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Party register stand-in for every `@QuarkusTest`: no test JVM serves party-service. Every party is
 * an INDIVIDUAL unless a test registers it otherwise, so the enrolment guard (#10281 item 1) is
 * exercised through real HTTP while the pre-existing enrolment ITs keep enrolling persons.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class StubPartyTypeLookup : PartyTypeLookup {

    override suspend fun partyType(partyId: UUID): String? {
        if (partyId in UNAVAILABLE) error("party register unreachable (test)")
        return TYPES[partyId] ?: "INDIVIDUAL"
    }

    companion object {
        val TYPES: MutableMap<UUID, String> = ConcurrentHashMap()
        val UNAVAILABLE: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    }
}
