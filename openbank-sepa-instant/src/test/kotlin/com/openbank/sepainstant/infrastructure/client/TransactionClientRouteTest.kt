// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.clients.transaction.api.TransactionsApi
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0319 D3: [TransactionServiceClient] is a sub-interface of the client GENERATED from
 * transaction-service's own `openapi.yaml`. The route is a LITERAL here, retyped from
 * transaction-service's `TransactionResource` - never derived from the client, or the test would
 * move with the generator and could not fail. Only the route the generated interface would issue
 * is read off its annotations, so a spec change that moves it reddens here instead of in
 * production. `SepaInstantTransactionServicePactConsumerTest` pins the same literal for the provider replay.
 */
class TransactionClientRouteTest {

    @Test
    fun `the settlement client issues POST to the contracted transaction route`() {
        assertThat(TransactionsApi::class.java.isAssignableFrom(TransactionServiceClient::class.java)).isTrue()
        val initiate = TransactionsApi::class.java.methods.single { it.name == "initiateTransaction" }
        assertThat(initiate.isAnnotationPresent(POST::class.java)).isTrue()
        val prefix = TransactionsApi::class.java.getAnnotation(Path::class.java).value
        assertThat(prefix + initiate.getAnnotation(Path::class.java)?.value.orEmpty())
            .isEqualTo("/api/v1/transactions")
    }
}
