// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.integration

import com.openbank.statement.application.port.`in`.RunCloseUseCase
import com.openbank.statement.domain.model.CloseRun
import com.openbank.statement.domain.model.CloseTrigger
import com.openbank.statement.it.PostgresTestResource
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression coverage for ADR-0237's statement close adoption: the defect is in scheduler
 * dispatch, so calling `monthlyClose()` directly cannot prove the reactive close use case is
 * reached on the scheduler's Vert.x context. This profile runs the actual cron every two seconds
 * and replaces only the expensive close orchestration with a recording use case.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(PeriodCloseSchedulerVertxContextIT.InMemoryKafkaResource::class)
@TestProfile(PeriodCloseSchedulerVertxContextIT.FastSchedulerProfile::class)
class PeriodCloseSchedulerVertxContextIT {

    class FastSchedulerProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.statement.close-cron" to "*/2 * * * * ?",
            "openbank.statement.scheduled-close.enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(RecordingRunCloseUseCase::class.java)
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("account-events-in") +
                InMemoryConnector.switchOutgoingChannelsToInMemory("statement-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Alternative
    @ApplicationScoped
    class RecordingRunCloseUseCase : RunCloseUseCase {
        override fun runClose(trigger: CloseTrigger): Uni<CloseRun> {
            if (trigger == CloseTrigger.SCHEDULED) invocations.incrementAndGet()
            return Uni.createFrom().item(mockk<CloseRun>())
        }

        companion object {
            val invocations = AtomicInteger(0)
        }
    }

    @BeforeEach
    fun reset() {
        RecordingRunCloseUseCase.invocations.set(0)
    }

    @Test
    fun `the real scheduler dispatch reaches the close use case`() {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (RecordingRunCloseUseCase.invocations.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }

        assertThat(RecordingRunCloseUseCase.invocations.get())
            .describedAs(
                "the real cron must reach the reactive close use case; a direct method call cannot " +
                    "prove scheduler context and would stay green if dispatch aborted before the use case",
            )
            .isPositive()
    }

    private companion object {
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
