// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.contract

import au.com.dius.pact.provider.junitsupport.State
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The broker and git-pact verifiers load the same provider contracts. The former publishes the
 * verdict which Test Intelligence retains, so every state handled by the PR-time verifier must
 * also be available to the broker verifier. Otherwise a locally green replay can become a red
 * deploy verdict solely because its fixture is missing in the publish lane.
 */
class ConsentPactBrokerStateCoverageTest {

    @Test
    fun `broker verifier handles every state handled by git-pact verifier`() {
        val gitPactStates = statesOn(ConsentPactProviderVerificationTest::class.java)
        val brokerStates = statesOn(ConsentPactBrokerProviderVerificationTest::class.java)

        assertThat(brokerStates).containsAll(gitPactStates)
    }

    private fun statesOn(type: Class<*>): Set<String> = type.methods
        .flatMap { method -> method.getAnnotation(State::class.java)?.value?.asList().orEmpty() }
        .toSet()
}
