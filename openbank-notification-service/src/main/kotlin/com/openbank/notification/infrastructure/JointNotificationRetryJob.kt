// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.notification.application.NotificationConsumer
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Reclaims JOINT prompts stranded after persistence without minting another notification row. */
@ApplicationScoped
@Startup
class JointNotificationRetryJob {
    @Inject lateinit var repository: NotificationRepository

    @Inject lateinit var consumer: NotificationConsumer

    @Inject lateinit var clock: Clock

    @Inject lateinit var metrics: MeterRegistry

    @Inject lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    /** A suspend scheduler supplies the Vert.x context required by Hibernate Reactive. */
    @Suppress("TooGenericExceptionCaught") // One bad row must not block later claims or future ticks.
    @Scheduled(
        every = "\${openbank.notification.joint-retry.interval:1m}",
        identity = "joint-notification-retry",
        concurrentExecution = SKIP,
    )
    suspend fun retryPending() {
        val now = Instant.now(clock)
        try {
            val rows = repository.claimStaleJointPending(
                now = now,
                olderThan = now.minus(INITIAL_GRACE),
                staleClaim = now.minus(CLAIM_LEASE),
                limit = BATCH_SIZE,
            ).awaitSuspending()
            for (notificationId in rows) {
                try {
                    consumer.retryJointPending(notificationId).awaitSuspending()
                    metrics.counter(RETRY_METRIC, "result", "completed").increment()
                } catch (err: Exception) {
                    metrics.counter(RETRY_METRIC, "result", "failed").increment()
                    Log.errorf(err, "JOINT notification retry failed for notificationId=%s", notificationId)
                }
            }
            liveness?.recordSuccess()
        } catch (err: Exception) {
            metrics.counter(RETRY_METRIC, "result", "claim_failed").increment()
            Log.errorf(err, "JOINT notification retry claim failed")
        }
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val RETRY_METRIC = "openbank_notification_joint_retry_total"
        const val WORKFLOW_NAME = "joint-notification-retry"
        val INITIAL_GRACE: Duration = Duration.ofMinutes(2)
        val CLAIM_LEASE: Duration = Duration.ofMinutes(5)
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
