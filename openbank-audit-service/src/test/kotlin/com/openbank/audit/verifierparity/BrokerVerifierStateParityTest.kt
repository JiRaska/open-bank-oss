// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.verifierparity

import au.com.dius.pact.provider.junitsupport.State
import com.openbank.audit.contract.AuditNegativeAuthProviderVerificationTest
import com.openbank.audit.contract.AuditPactBrokerProviderVerificationTest
import com.openbank.audit.contract.AuditPactProviderVerificationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Not a contract test: a reflection check that the broker-sourced verifier handles every provider
 * state the git-pact verifiers do. Kept out of the `contract` package so the adversarial-contract
 * gate (which needs a 401/403 case in every contract test) does not ask a parity check for one.
 */
class BrokerVerifierStateParityTest {

    @Test
    fun `broker verifier handles every state the git-pact verifiers handle`() {
        val gitPactStates = statesOn(AuditPactProviderVerificationTest::class.java) +
            statesOn(AuditNegativeAuthProviderVerificationTest::class.java)
        assertThat(statesOn(AuditPactBrokerProviderVerificationTest::class.java)).containsAll(gitPactStates)
    }

    private fun statesOn(type: Class<*>): Set<String> = type.methods
        .flatMap { method -> method.getAnnotation(State::class.java)?.value?.asList().orEmpty() }
        .toSet()
}
