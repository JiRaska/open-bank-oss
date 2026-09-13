// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.document.integration

import com.openbank.document.infrastructure.outbox.DocumentOutboxDispatcher
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import io.quarkus.arc.ClientProxy
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

/**
 * Second module in the #5128 finding 2 proof (see `PartyOutboxDispatcherCdiIT` in
 * openbank-party-service for the full rationale): `AbstractOutboxDispatcher.metrics` is declared
 * in openbank-libs-runtime and consumed here, in a completely separate Gradle module, via a
 * constructor parameter. This asserts a real Quarkus Arc container resolves it end to end —
 * exactly the cross-module CDI wiring the original field-injected version shipped with no test
 * proving at all.
 *
 * No `@QuarkusTestResource` — bean *injection* is lazy and never touches Postgres/Kafka.
 */
@QuarkusTest
class DocumentOutboxDispatcherCdiIT {

    @Inject
    lateinit var dispatcher: DocumentOutboxDispatcher

    @Test
    fun `DocumentOutboxDispatcher is a real CDI bean with a real DomainMetrics resolved through it`() {
        assertThat(dispatcher).isNotNull

        val real = ClientProxy.unwrap(dispatcher)

        val metricsField: Field = AbstractOutboxDispatcher::class.java.getDeclaredField("metrics")
        metricsField.isAccessible = true
        val resolved = metricsField.get(real)

        assertThat(resolved)
            .describedAs("metrics must be a real container-resolved DomainMetrics, not left uninitialized")
            .isNotNull
            .isInstanceOf(DomainMetrics::class.java)
    }
}
