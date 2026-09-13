// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import au.com.dius.pact.provider.junitsupport.State
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The PR-time folder replay and main-push broker replay exercise the same provider pacts. Their
 * state handlers therefore form one contract surface: missing a state in the broker verifier lets
 * a PR pass and only blocks deployment later at `can-i-deploy`.
 */
class ProductCatalogPactStateParityTest {
    @Test
    fun `broker and folder provider verifiers declare identical Pact states`() {
        assertThat(statesOf(ProductCatalogPactBrokerProviderVerificationTest::class.java))
            .containsExactlyInAnyOrderElementsOf(statesOf(ProductCatalogPactProviderVerificationTest::class.java))
    }

    private fun statesOf(type: Class<*>): Set<String> = type.declaredMethods
        .flatMap { method -> method.getAnnotation(State::class.java)?.value?.asList().orEmpty() }
        .toSet()
}
