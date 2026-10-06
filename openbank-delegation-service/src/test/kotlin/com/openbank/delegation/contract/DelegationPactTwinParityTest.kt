// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junitsupport.State
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegationPactTwinParityTest {

    @Test
    fun `broker and folder providers cover the same states and payloads`() {
        val folder = DelegationEventPactFolderProviderVerificationTest()
        val broker = DelegationEventPactBrokerProviderVerificationTest()
        val objectMapper = jacksonObjectMapper()

        fun states(provider: Any): Set<String> = provider.javaClass.declaredMethods
            .flatMap { it.getAnnotation(State::class.java)?.value?.toList().orEmpty() }
            .toSet()

        fun payloads(provider: Any): Map<String, JsonNode> = provider.javaClass.declaredMethods
            .mapNotNull { method ->
                method.getAnnotation(PactVerifyProvider::class.java)?.value?.let { name ->
                    val payload = objectMapper.readTree(method.invoke(provider) as String) as ObjectNode
                    assertThat(payload.path("eventId").asText()).isNotBlank()
                    payload.remove("eventId") // Generated independently for each invocation.
                    name to payload
                }
            }
            .toMap()

        assertThat(states(broker)).isEqualTo(states(folder))
        assertThat(payloads(broker)).isEqualTo(payloads(folder))
    }
}
