// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.party.integration

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.party.infrastructure.outbox.PartyOutboxDispatcher
import io.quarkus.arc.ClientProxy
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

/**
 * Proves the exact thing #5128 finding 2 said was unverified: that `AbstractOutboxDispatcher`'s
 * `metrics: DomainMetrics` constructor parameter — declared in `openbank-libs-runtime` and
 * consumed by a subclass in a completely different Gradle module — actually resolves through a
 * REAL Quarkus Arc container, not a hand-constructed test double.
 *
 * Every existing `*OutboxDispatcherTest` in the fleet (including this service's own
 * `PartyOutboxDispatcherTest`, if one exists) constructs its dispatcher directly with
 * `PartyOutboxDispatcher(repo, publisher, dispatchEnabled, metrics)`, bypassing CDI entirely — the
 * same shape that let the old field-injection version silently ship with zero cross-module proof.
 * `@Inject`ing the real bean here and reading `metrics` back via reflection is the only way to
 * distinguish "compiles and passes a mock" from "the container actually wires it".
 *
 * No `@QuarkusTestResource` (no Postgres/Kafka) — CDI bean *injection* is lazy and does not touch
 * the database or the broker; only calling `dispatchScheduledBatch()` would need those, and that is
 * not what this test is proving.
 */
@QuarkusTest
class PartyOutboxDispatcherCdiIT {

    @Inject
    lateinit var dispatcher: PartyOutboxDispatcher

    @Test
    fun `PartyOutboxDispatcher is a real CDI bean with a real DomainMetrics resolved through it`() {
        assertThat(dispatcher).isNotNull

        // `@Inject`ing a normal-scoped (`@ApplicationScoped`) bean hands back Arc's generated
        // CLIENT PROXY, not the contextual instance — reading a field off the proxy directly reads
        // the proxy's own (uninitialized) storage, not the real bean's, and proves nothing.
        // `ClientProxy.unwrap` is the documented way to reach the actual instance (see
        // PdpBeanSelectionIT in openbank-mcp-service for the same pattern).
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
