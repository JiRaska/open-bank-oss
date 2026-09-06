// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.contract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the assumption the two provider-verification classes are split on.
 *
 * They divide the committed pacts by PROVIDER STATE: one filter matches
 * [NEGATIVE_AUTH_STATE], the other matches everything else. pact-jvm's `ByProviderState`
 * predicate is an `anyMatch` over the interaction's states, so an interaction that declares NO
 * state matches neither filter and is verified by NEITHER class — silently, with both classes
 * green. That is the exact "green about work it never did" shape the replay exists to prevent, and
 * nothing else in CI would notice it.
 *
 * This test is cheap and needs no Quarkus: it reads the committed pacts directly.
 */
class TransactionPactStateCoverageTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `every committed interaction for this provider declares a provider state`() {
        val pacts = File("../pacts")
            .listFiles { f -> f.name.endsWith("-openbank-transaction-service.json") }
            ?.sorted()
            .orEmpty()

        // A wrong path would make this test pass by finding nothing — the failure mode it is
        // written against. Assert the corpus is non-empty before asserting anything about it.
        assertThat(pacts).describedAs("committed pacts naming transaction-service as provider").isNotEmpty()

        val stateless = pacts.flatMap { file ->
            val pact: Map<String, Any?> = mapper.readValue(file)
            @Suppress("UNCHECKED_CAST")
            val interactions = pact["interactions"] as? List<Map<String, Any?>> ?: emptyList()
            interactions
                .filter { interaction ->
                    val states = interaction["providerStates"] as? List<*>
                    val legacy = interaction["providerState"] as? String
                    states.isNullOrEmpty() && legacy.isNullOrBlank()
                }
                .map { "${file.name}: ${it["description"]}" }
        }

        assertThat(stateless)
            .describedAs(
                "interactions with no provider state would be filtered out of BOTH " +
                    "TransactionPactFolderProviderVerificationTest and " +
                    "TransactionNegativeAuthPactVerificationTest, and verified by neither",
            )
            .isEmpty()
    }
}
