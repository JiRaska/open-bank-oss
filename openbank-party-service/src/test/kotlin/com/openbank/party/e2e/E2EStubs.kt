// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.e2e

import com.openbank.party.application.port.out.MarketingConsentForwardingPort
import com.openbank.party.application.port.out.PartyAccountGuardPort
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Merge-guard stand-in for every `@QuarkusTest`: no test JVM serves account-service, and the
 * production client is fail-closed (a transport error aborts the merge), which would make the
 * open-account merge precondition unreachable in tests. The E2E suite seeds [openAccounts] per
 * party to exercise both the blocking and the permitting branch through real HTTP.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class E2EPartyAccountGuard : PartyAccountGuardPort {

    val openAccounts: MutableMap<UUID, List<String>> = ConcurrentHashMap()

    override suspend fun findOpenAccounts(partyId: UUID): List<String> = openAccounts[partyId] ?: emptyList()
}

/**
 * consent-service forwarding stand-in for every `@QuarkusTest`: the real adapter calls
 * consent-service synchronously and nothing in the test JVM serves it. The E2E suite asserts
 * the toggle journey (optimistic response + exactly-one grant) through real HTTP while the
 * projection that owns `parties.consent_marketing` stays out of scope here.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class E2EMarketingConsentForwarding : MarketingConsentForwardingPort {

    val grants: MutableMap<UUID, UUID> = ConcurrentHashMap()
    val revocations: MutableMap<UUID, UUID> = ConcurrentHashMap()

    override suspend fun grant(partyId: UUID): UUID = UUID.randomUUID().also { grants[partyId] = it }

    override suspend fun revoke(partyId: UUID, consentId: UUID, reason: String) {
        revocations[partyId] = consentId
    }
}
