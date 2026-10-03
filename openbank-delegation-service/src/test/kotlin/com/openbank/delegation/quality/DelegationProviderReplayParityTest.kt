// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.quality

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junitsupport.State
import com.openbank.delegation.contract.DelegationEventPactBrokerProviderVerificationTest
import com.openbank.delegation.contract.DelegationEventPactFolderProviderVerificationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DelegationProviderReplayParityTest {
    @Test
    fun `broker and folder replay handle the same interactions`() {
        val folder = DelegationEventPactFolderProviderVerificationTest::class.java
        val broker = DelegationEventPactBrokerProviderVerificationTest::class.java

        assertEquals(stateNames(folder), stateNames(broker), "Broker and folder provider states differ")
        assertEquals(producerNames(folder), producerNames(broker), "Broker and folder message producers differ")
    }

    private fun stateNames(type: Class<*>): Set<String> =
        type.declaredMethods.flatMap { method ->
            method.getAnnotationsByType(State::class.java).flatMap { it.value.toList() }
        }.toSet()

    private fun producerNames(type: Class<*>): Set<String> =
        type.declaredMethods.flatMap { method ->
            method.getAnnotationsByType(PactVerifyProvider::class.java).map { it.value }
        }.toSet()
}
