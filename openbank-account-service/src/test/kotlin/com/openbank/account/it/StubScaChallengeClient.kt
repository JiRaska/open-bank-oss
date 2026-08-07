// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.it

import com.openbank.account.application.port.out.ScaChallengeClient
import com.openbank.account.application.port.out.ScaChallengeSnapshot
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-only SCA adapter: sca-service does not run in the IT stack, so the decide leg's challenge
 * verification is answered here.
 *
 * `getChallenge` reports **PENDING**, deliberately. That is the state the customer path actually
 * produces: a decoupled challenge (PUSH_NOTIFICATION / BIOMETRIC) holds a signature-verified device
 * decision while sitting at PENDING, because nothing a customer can reach calls sca-service's
 * `verify()`. This stub used to answer "COMPLETED" — a state the app never reaches — which is
 * exactly why the service's `status == "COMPLETED"` pre-check passed every test while making owner
 * approval impossible in production. A fixture that reports a state the real caller cannot be in
 * does not test the flow, it hides it.
 *
 * `consumeChallenge` is what promotes and spends it, mirroring sca-service: a second consume of the
 * same challenge throws, so an IT can prove single-use.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class StubScaChallengeClient : ScaChallengeClient {

    override suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot = ScaChallengeSnapshot(
        id = challengeId,
        partyId = party.get(),
        purpose = purpose.get(),
        status = "PENDING",
    )

    override suspend fun consumeChallenge(challengeId: UUID, expectedPartyId: UUID): ScaChallengeSnapshot {
        consumeCount.incrementAndGet()
        check(consumed.add(challengeId)) { "SCA challenge $challengeId already consumed" } // sca-service: 409
        check(expectedPartyId == party.get()) { "SCA challenge $challengeId does not belong to $expectedPartyId" }
        return ScaChallengeSnapshot(
            id = challengeId,
            partyId = party.get(),
            purpose = purpose.get(),
            status = "COMPLETED",
        )
    }

    companion object {
        val party: AtomicReference<UUID> = AtomicReference(UUID.randomUUID())
        val purpose: AtomicReference<String> = AtomicReference("SAVINGS_WITHDRAW_APPROVAL")
        val consumeCount: AtomicInteger = AtomicInteger(0)
        val consumed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

        fun reset() {
            consumeCount.set(0)
            consumed.clear()
        }
    }
}
