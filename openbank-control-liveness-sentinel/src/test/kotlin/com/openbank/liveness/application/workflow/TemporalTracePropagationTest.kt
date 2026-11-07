// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.workflow

import com.openbank.libs.temporal.TemporalClientProducer
import com.openbank.libs.temporal.TemporalConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Proves the caller's trace id reaches a Temporal ACTIVITY.
 *
 * The workflow below does nothing but ask its activity "which trace am I in?". The activity reads
 * `Span.current()`, exactly what any span it starts would parent on. The environment is built with
 * [TemporalClientProducer.workflowClientOptions] -- the options production clients get -- so
 * dropping the propagator there turns the first test red. The second test is the "before": the same
 * workflow on a client without the propagator, where the activity sees no trace at all.
 */
class TemporalTracePropagationTest {

    @WorkflowInterface
    interface TraceProbeWorkflow {
        @WorkflowMethod
        fun run(): String
    }

    @ActivityInterface
    interface TraceProbeActivity {
        @ActivityMethod
        fun currentTraceId(): String
    }

    class TraceProbeActivityImpl : TraceProbeActivity {
        override fun currentTraceId(): String = Span.current().spanContext.traceId
    }

    class TraceProbeWorkflowImpl : TraceProbeWorkflow {
        private val activity = Workflow.newActivityStub(
            TraceProbeActivity::class.java,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build(),
        )

        override fun run(): String = activity.currentTraceId()
    }

    private val callerTraceId = "0af7651916cd43dd8448eb211c80319c"

    private fun callerSpan() = Span.wrap(
        SpanContext.create(callerTraceId, "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault()),
    )

    private fun productionClientOptions(): WorkflowClientOptions {
        val config = mockk<TemporalConfig>()
        every { config.namespace() } returns "UnitTest"
        return TemporalClientProducer(config, SimpleMeterRegistry()).workflowClientOptions()
    }

    private fun activityTraceIdSeenFrom(clientOptions: WorkflowClientOptions): String {
        val env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(clientOptions).build(),
        )
        try {
            val taskQueue = "trace-probe-${UUID.randomUUID()}"
            val worker = env.newWorker(taskQueue)
            worker.registerWorkflowImplementationTypes(TraceProbeWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(TraceProbeActivityImpl())
            env.start()
            val stub = env.workflowClient.newWorkflowStub(
                TraceProbeWorkflow::class.java,
                WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build(),
            )
            return callerSpan().makeCurrent().use { stub.run() }
        } finally {
            env.close()
        }
    }

    @Test
    fun `an activity runs inside the trace of the caller that started the workflow`() {
        assertThat(activityTraceIdSeenFrom(productionClientOptions())).isEqualTo(callerTraceId)
    }

    @Test
    fun `without the propagator the activity sees no trace - the behaviour before this change`() {
        val withoutPropagator = productionClientOptions().toBuilder().setContextPropagators(emptyList()).build()

        val seen = activityTraceIdSeenFrom(withoutPropagator)

        assertThat(seen).isNotEqualTo(callerTraceId)
        assertThat(seen).isEqualTo("00000000000000000000000000000000")
    }
}
