// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.out.SignatureIntents
import com.openbank.lending.application.usecase.LendingService
import com.openbank.libs.messaging.SyntheticTaintKafkaRail
import com.openbank.libs.observability.DomainMetrics
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger
import java.time.Duration

@Startup
@ApplicationScoped
class SignatureRecoveryScheduler(
    private val intents: SignatureIntents,
    private val lending: LendingService,
    metrics: DomainMetrics,
) {
    private val liveness = metrics.registerWorkflowLiveness("lending-signature-recovery", Duration.ofMinutes(1))

    @Scheduled(
        identity = "lending-signature-recovery",
        every = "{lending.origination.signature-recovery-interval:10s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun recover() {
        val pending = intents.claimDue(BATCH_SIZE).awaitSuspending()
        pending.forEach { intent ->
            SyntheticTaintKafkaRail.withTaint(intent.synthetic) {
                lending.finishSignatureIntent(intent).replaceWith(Unit)
                    .onFailure { failure -> failure is RuntimeException && failure !is CancellationException }
                    .recoverWithItem { failure: Throwable ->
                        log.warnf(
                            "Signature recovery could not complete operation %s (%s)",
                            intent.id,
                            failure.javaClass.simpleName,
                        )
                        Unit
                    }.awaitSuspending()
            }
        }
        liveness.recordSuccess()
    }

    private companion object {
        val log: Logger = Logger.getLogger(SignatureRecoveryScheduler::class.java)
        const val BATCH_SIZE = 20
    }
}
