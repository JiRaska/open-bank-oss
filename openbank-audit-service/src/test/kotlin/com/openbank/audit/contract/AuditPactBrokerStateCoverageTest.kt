// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.contract

import au.com.dius.pact.provider.junitsupport.State
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuditPactBrokerStateCoverageTest {

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
