// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.libs.temporal.TemporalClientProducer
import com.openbank.libs.temporal.TemporalConfig
import com.openbank.transaction.domain.saga.SagaState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.temporal.api.enums.v1.VersioningBehavior
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentVersionRequest
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerFactoryOptions
import io.temporal.worker.WorkerOptions
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real server routing; in-memory TestWorkflowEnvironment does not implement deployment routing. */
class PaymentWorkerVersioningIT {
    @Test
    fun `new starts follow current version while running payments remain pinned across rollback`() {
        GenericContainer(DockerImageName.parse(TEMPORAL_IMAGE))
            .withExposedPorts(PORT)
            .withCommand("server", "start-dev", "--ip", "0.0.0.0", "--port", PORT.toString(), "-n", NAMESPACE)
            .use { server ->
                server.start()
                val registry = SimpleMeterRegistry()
                val config = testConfig("${server.host}:${server.getMappedPort(PORT)}")
                // Exercise the real shared producer, including its lazy gRPC and metrics branch.
                val client = TemporalClientProducer(config, registry).workflowClient()
                val a = RecordingActivities()
                val b = RecordingActivities()
                val factories = mutableListOf<WorkerFactory>()
                try {
                    waitForNamespace(client)
                    factories += startWorker(client, BUILD_A, a)
                    factories += startWorker(client, BUILD_B, b)
                    waitForPollers(client, BUILD_A)
                    waitForPollers(client, BUILD_B)

                    route(client, BUILD_A)
                    val first = startPayment(client, FIRST)
                    a.waitForHold(FIRST)
                    assertPinned(first, BUILD_A)

                    route(client, BUILD_B)
                    val second = startPayment(client, SECOND)
                    b.waitForHold(SECOND)
                    assertPinned(second, BUILD_B)
                    assertPinned(first, BUILD_A)
                    a.release(FIRST)
                    completed(first)
                    assertThat(a.calls[FIRST]).containsExactly("hold", "journal", "completed")
                    assertThat(b.calls).doesNotContainKey(FIRST)

                    route(client, BUILD_A)
                    val third = startPayment(client, THIRD)
                    a.waitForHold(THIRD)
                    assertPinned(third, BUILD_A)
                    a.release(THIRD)
                    completed(third)
                    b.release(SECOND)
                    completed(second)
                    assertPinned(second, BUILD_B)
                    assertThat(b.calls[SECOND]).containsExactly("hold", "journal", "completed")
                    assertThat(a.calls[THIRD]).containsExactly("hold", "journal", "completed")
                    assertThat(a.calls).doesNotContainKey(SECOND)
                    assertThat(b.calls).doesNotContainKey(THIRD)
                    await().atMost(WAIT).untilAsserted {
                        assertThat(registry.meters).anyMatch { it.id.name.startsWith("temporal") }
                    }
                } finally {
                    a.releaseAll()
                    b.releaseAll()
                    factories.forEach { it.shutdownNow() }
                    factories.forEach { it.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS) }
                    client.workflowServiceStubs.shutdownNow()
                    client.workflowServiceStubs.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
                    client.workflowServiceStubs.options.metricsScope.close()
                    registry.close()
                }
            }
    }

    private fun testConfig(target: String): TemporalConfig = object : TemporalConfig {
        override fun enabled() = true
        override fun serverUrl() = target
        override fun namespace() = NAMESPACE
        override fun taskQueue() = TASK_QUEUE
        override fun metricsEnabled() = true
    }

    private fun waitForNamespace(client: WorkflowClient) {
        await().atMost(WAIT).ignoreExceptions().untilAsserted {
            val ns = client.workflowServiceStubs.blockingStub().describeNamespace(
                DescribeNamespaceRequest.newBuilder().setNamespace(NAMESPACE).build(),
            )
            assertThat(ns.namespaceInfo.name).isEqualTo(NAMESPACE)
        }
    }

    private fun startWorker(client: WorkflowClient, build: String, activities: RecordingActivities): WorkerFactory {
        val config = object : PaymentWorkerVersioningConfig {
            override fun enabled() = true
            override fun deploymentName() = Optional.of(DEPLOYMENT)
            override fun buildId() = Optional.of(build)
        }
        val factory = WorkerFactory.newInstance(
            client,
            WorkerFactoryOptions.newBuilder().setWorkflowCacheSize(0).build(),
        )
        try {
            val worker = factory.newWorker(
                TASK_QUEUE,
                WorkerOptions.newBuilder(paymentWorkerOptions(config)).setDisableEagerExecution(true).build(),
            )
            worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(activities)
            factory.start()
            return factory
        } catch (failure: Exception) {
            factory.shutdownNow()
            throw failure
        }
    }

    private fun waitForPollers(client: WorkflowClient, build: String) {
        await().atMost(WAIT).ignoreExceptions().untilAsserted {
            val version = client.workflowServiceStubs.blockingStub().describeWorkerDeploymentVersion(
                DescribeWorkerDeploymentVersionRequest.newBuilder()
                    .setNamespace(NAMESPACE)
                    .setDeploymentVersion(
                        io.temporal.api.deployment.v1.WorkerDeploymentVersion.newBuilder()
                            .setDeploymentName(DEPLOYMENT).setBuildId(build),
                    ).build(),
            )
            assertThat(version.workerDeploymentVersionInfo.taskQueueInfosList).isNotEmpty()
        }
    }

    private fun route(client: WorkflowClient, build: String) {
        await().atMost(WAIT).ignoreExceptions().untilAsserted {
            val rpc = client.workflowServiceStubs.blockingStub()
            val deployment = rpc.describeWorkerDeployment(
                DescribeWorkerDeploymentRequest.newBuilder()
                    .setNamespace(NAMESPACE).setDeploymentName(DEPLOYMENT).build(),
            )
            rpc.setWorkerDeploymentCurrentVersion(
                SetWorkerDeploymentCurrentVersionRequest.newBuilder()
                    .setNamespace(NAMESPACE).setDeploymentName(DEPLOYMENT).setBuildId(build)
                    .setConflictToken(deployment.conflictToken)
                    .setIdentity("synthetic-versioning-test").build(),
            )
        }
    }

    private fun startPayment(client: WorkflowClient, id: UUID): WorkflowStub {
        val workflow = client.newWorkflowStub(
            PaymentWorkflow::class.java,
            WorkflowOptions.newBuilder().setWorkflowId("synthetic-payment-$id").setTaskQueue(TASK_QUEUE).build(),
        )
        return WorkflowStub.fromTyped(workflow).also { it.start(id) }
    }

    private fun assertPinned(stub: WorkflowStub, build: String) {
        val info = stub.describe().workflowExecutionInfo.versioningInfo
        assertThat(info.behavior).isEqualTo(VersioningBehavior.VERSIONING_BEHAVIOR_PINNED)
        assertThat(info.deploymentVersion.deploymentName).isEqualTo(DEPLOYMENT)
        assertThat(info.deploymentVersion.buildId).isEqualTo(build)
    }

    private fun completed(stub: WorkflowStub) {
        assertThat(stub.getResult(WAIT.seconds, TimeUnit.SECONDS, SagaState::class.java)).isEqualTo(SagaState.COMPLETED)
    }

    private class RecordingActivities : PaymentActivities {
        val calls = ConcurrentHashMap<UUID, CopyOnWriteArrayList<String>>()
        private val holds = ConcurrentHashMap<UUID, CountDownLatch>()

        override fun placeHold(transactionId: UUID): UUID {
            val latch = holds.computeIfAbsent(transactionId) { CountDownLatch(1) }
            calls.computeIfAbsent(transactionId) { CopyOnWriteArrayList() }.add("hold")
            check(latch.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "Synthetic activity was not released" }
            return transactionId
        }

        override fun postJournal(transactionId: UUID): UUID {
            calls.getValue(transactionId).add("journal")
            return transactionId
        }

        override fun markCompleted(transactionId: UUID) {
            calls.getValue(transactionId).add("completed")
        }
        override fun releaseHold(holdId: UUID) = error("Unexpected compensation")
        override fun reverseJournal(journalId: UUID) = error("Unexpected compensation")
        override fun markFailed(transactionId: UUID, reason: String) = error("Unexpected payment failure")

        fun waitForHold(id: UUID) {
            await().atMost(WAIT).untilAsserted { assertThat(calls[id]).containsExactly("hold") }
        }
        fun release(id: UUID) {
            holds.getValue(id).countDown()
        }
        fun releaseAll() {
            holds.values.forEach { it.countDown() }
        }
    }

    private companion object {
        const val TEMPORAL_IMAGE = "temporalio/temporal:1.8.2@" +
            "sha256:cf86707827fac99e4d1c4a47dc11b105382d796199c7bd41fb3213fb0471628e"
        const val PORT = 7233
        const val NAMESPACE = "synthetic-payment-versioning"
        const val TASK_QUEUE = "synthetic-payment-versioning"
        const val DEPLOYMENT = "synthetic-payment"
        const val BUILD_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BUILD_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHUTDOWN_SECONDS = 10L
        const val HOLD_SECONDS = 180L
        val WAIT: Duration = Duration.ofSeconds(45)
        val FIRST: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val SECOND: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val THIRD: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
    }
}
