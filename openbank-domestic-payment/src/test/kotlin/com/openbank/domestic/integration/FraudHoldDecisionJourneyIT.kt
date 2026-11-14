// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.workflow.DomesticPaymentActivities
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflowImpl
import com.openbank.domestic.application.workflow.FraudGateAction
import com.openbank.domestic.application.workflow.FraudRejectCause
import com.openbank.domestic.application.workflow.domesticPaymentWorkflowId
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.infrastructure.observability.FraudHoldDurationMeterFilter
import com.openbank.libs.fincrime.ScreeningDecision
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import com.uber.m3.tally.RootScopeBuilder
import com.uber.m3.tally.Scope
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.reporter.MicrometerClientStatsReporter
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val AML_TOPIC = "openbank.aml.events"
private const val DLQ_TOPIC = "openbank.dlq.domestic-payment.fraud-hold-resolution-in"
private const val AML_CONSUMER_GROUP = "domestic-payment-fraud-hold-resolution"
private const val WAIT_SECONDS = 30L

/**
 * Runs the domestic Kafka consumer and workflow against local Redpanda and Temporal. The AML
 * status event is a contract fixture. Screening, clearing, and settlement activities are isolated
 * test doubles, so the journey cannot contact an external rail or book funds.
 */
@QuarkusTest
@TestProfile(FraudHoldDecisionJourneyIT.Profile::class)
@QuarkusTestResource(value = FraudHoldJourneyKafkaResource::class, restrictToAnnotatedClass = true)
class FraudHoldDecisionJourneyIT {
    @Inject
    lateinit var workflowClient: WorkflowClient

    @Inject
    lateinit var runtime: FraudHoldJourneyRuntime

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrapServers: String

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "mp.messaging.incoming.fraud-hold-resolution-in.enabled" to "true",
            "quarkus.otel.sdk.disabled" to "true",
            "quarkus.scheduler.enabled" to "false",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FraudHoldJourneyRuntime::class.java)
    }

    @ParameterizedTest
    @CsvSource("CLEARED, SETTLED, RELEASED", "BLOCKED, REJECTED, REJECTED")
    fun `only the correlated AML decision ends a fraud hold`(
        amlStatus: String,
        expectedStatus: DomesticPaymentStatus,
        expectedOutcome: String,
    ) {
        val paymentId = UUID.randomUUID()
        val workflowId = domesticPaymentWorkflowId(paymentId)
        val workflow = startWorkflow(paymentId, workflowId)
        await("the workflow to start its 48-hour hold timer") {
            history(workflowId).any { event ->
                event.eventType == EventType.EVENT_TYPE_TIMER_STARTED &&
                    event.timerStartedEventAttributes.startToFireTimeout.seconds ==
                    DomesticPaymentWorkflowImpl.FRAUD_HOLD_TIMEOUT.seconds
            }
        }

        kafkaProducer().use { producer ->
            val unrelatedCaseId = UUID.randomUUID()
            val unrelated = producer.publish(
                unrelatedCaseId,
                decision(unrelatedCaseId, paymentId, "CLEARED", "unrelated-case"),
            )
            awaitAcknowledged(unrelated.offset())
            assertThat(history(workflowId).map { it.eventType })
                .doesNotContain(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
            runtime.advance(HOLD_ELAPSED)
            val reviewCaseId = UUID.randomUUID()
            producer.publish(reviewCaseId, decision(reviewCaseId, paymentId, amlStatus, "aml-$paymentId-FRAUD_REVIEW"))
        }

        await("the Kafka consumer to signal the workflow") {
            history(workflowId).any { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED }
        }
        assertThat(workflow.getResult(WAIT_SECONDS, TimeUnit.SECONDS, DomesticPaymentStatus::class.java))
            .isEqualTo(expectedStatus)
        assertThat(history(workflowId).count { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED })
            .isEqualTo(1)
        assertActivities(paymentId, expectedStatus)
        assertHoldDuration(expectedOutcome)
    }

    private fun startWorkflow(paymentId: UUID, workflowId: String): WorkflowStub {
        every { runtime.activities.screenPayment(paymentId) } returns ScreeningDecision.CLEAR
        every { runtime.activities.fraudGate(paymentId) } returns FraudGateAction.HOLD
        every { runtime.activities.submitScheme(paymentId) } returns DomesticPaymentStatus.SENT_TO_CLEARING
        every { runtime.activities.settlePayment(paymentId) } returns DomesticPaymentStatus.SETTLED
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(FraudHoldJourneyRuntime.TASK_QUEUE)
            .build()
        val workflow = WorkflowStub.fromTyped(
            workflowClient.newWorkflowStub(DomesticPaymentWorkflow::class.java, options),
        )
        workflow.start(paymentId)
        return workflow
    }

    private fun history(workflowId: String) = workflowClient.fetchHistory(workflowId).events

    private fun assertActivities(paymentId: UUID, expectedStatus: DomesticPaymentStatus) {
        if (expectedStatus == DomesticPaymentStatus.SETTLED) {
            verify(exactly = 1) { runtime.activities.submitScheme(paymentId) }
            verify(exactly = 1) { runtime.activities.settlePayment(paymentId) }
            verify(exactly = 0) { runtime.activities.rejectForFraud(paymentId, any()) }
        } else {
            verify(exactly = 1) { runtime.activities.rejectForFraud(paymentId, FraudRejectCause.REVIEW_REJECTED) }
            verify(exactly = 0) { runtime.activities.submitScheme(paymentId) }
            verify(exactly = 0) { runtime.activities.settlePayment(paymentId) }
        }
    }

    private fun assertHoldDuration(outcome: String) {
        await("the hold-duration sample for $outcome") { runtime.holdTimer(outcome)?.count() == 1L }
        val wait = checkNotNull(runtime.holdTimer(outcome)).totalTime(TimeUnit.SECONDS)
        assertThat(wait).isBetween(HOLD_ELAPSED.seconds.toDouble() - 1, HOLD_ELAPSED.seconds.toDouble() + 20)
    }

    private fun decision(caseId: UUID, paymentId: UUID, status: String, caseKey: String): String {
        val analyst = UUID.randomUUID()
        return mapper.writeValueAsString(
            mapOf(
                "caseId" to caseId,
                "partyId" to UUID.randomUUID(),
                "previousStatus" to "UNDER_REVIEW",
                "newStatus" to status,
                "decisionReason" to "Synthetic test decision",
                "assignedAnalyst" to analyst,
                "decidedBy" to analyst,
                "occurredAt" to Instant.now(),
                "transactionId" to paymentId,
                "alertCode" to "FRAUD_REVIEW",
                "idempotencyKey" to caseKey,
            ),
        )
    }

    private fun kafkaProducer() = KafkaProducer(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to false,
        ),
        StringSerializer(),
        StringSerializer(),
    )

    private fun KafkaProducer<String, String>.publish(caseId: UUID, payload: String) =
        send(ProducerRecord(AML_TOPIC, 0, caseId.toString(), payload)).get(WAIT_SECONDS, TimeUnit.SECONDS)

    private fun awaitAcknowledged(offset: Long) {
        Admin.create(mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)).use { admin ->
            val partition = TopicPartition(AML_TOPIC, 0)
            await("the consumer group to acknowledge the unrelated AML case") {
                val offsets = admin.listConsumerGroupOffsets(AML_CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata().get(WAIT_SECONDS, TimeUnit.SECONDS)
                (offsets[partition]?.offset() ?: -1) > offset
            }
        }
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!ready()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $description" }
            Thread.sleep(50)
        }
    }

    private companion object {
        val HOLD_ELAPSED: Duration = Duration.ofMinutes(2)
    }
}

class FraudHoldJourneyKafkaResource : QuarkusTestResourceLifecycleManager {
    private val containers = PostgresRedpandaRedisTestResource()

    override fun start(): Map<String, String> {
        containers.init(mapOf("db" to "openbank_domestic_fraud_journey_it"))
        try {
            val config = containers.start()
            val bootstrap = config.getValue("kafka.bootstrap.servers")
            Admin.create(mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
                val topics = listOf(AML_TOPIC, DLQ_TOPIC).map { NewTopic(it, 1, 1) }
                admin.createTopics(topics).all().get(WAIT_SECONDS, TimeUnit.SECONDS)
            }
            return config
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() = containers.stop()
}

@Alternative
@ApplicationScoped
class FraudHoldJourneyRuntime {
    private lateinit var environment: TestWorkflowEnvironment
    private lateinit var scope: Scope
    private val registry = SimpleMeterRegistry()

    val activities: DomesticPaymentActivities = mockk(relaxed = true)

    @PostConstruct
    fun start() {
        registry.config().meterFilter(FraudHoldDurationMeterFilter.filter())
        scope = RootScopeBuilder()
            .reporter(MicrometerClientStatsReporter(registry))
            .reportEvery(com.uber.m3.util.Duration.ofMillis(10.0))
        environment = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowServiceStubsOptions(WorkflowServiceStubsOptions.newBuilder().setMetricsScope(scope).build())
                .setMetricsScope(scope)
                .build(),
        )
        val worker = environment.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        environment.start()
    }

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = environment.workflowClient

    fun advance(duration: Duration) = environment.sleep(duration)

    fun holdTimer(outcome: String) = registry.find(DomesticPaymentWorkflowImpl.FRAUD_HOLD_DURATION_METRIC)
        .tag("outcome", outcome)
        .timer()

    @PreDestroy
    fun stop() {
        if (::environment.isInitialized) environment.close()
        if (::scope.isInitialized) scope.close()
        registry.close()
    }

    companion object {
        const val TASK_QUEUE = "domestic-fraud-hold-journey"
    }
}
