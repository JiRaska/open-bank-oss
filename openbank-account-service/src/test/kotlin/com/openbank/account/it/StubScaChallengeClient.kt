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
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-only SCA adapter: sca-service does not run in the IT stack, so the decide
 * leg's challenge verification is answered here. The deciding party is set by the
 * test per scenario; purpose and status always match the production contract.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class StubScaChallengeClient : ScaChallengeClient {

    override suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot = ScaChallengeSnapshot(
        id = challengeId,
        partyId = party.get(),
        purpose = purpose.get(),
        status = "COMPLETED",
    )

    companion object {
        val party: AtomicReference<UUID> = AtomicReference(UUID.randomUUID())
        val purpose: AtomicReference<String> = AtomicReference("SAVINGS_WITHDRAW_APPROVAL")
    }
}
