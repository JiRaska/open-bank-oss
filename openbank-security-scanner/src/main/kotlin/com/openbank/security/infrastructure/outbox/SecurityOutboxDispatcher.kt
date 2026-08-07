// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.outbox

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.security.infrastructure.persistence.repository.SecurityOutboxRepositoryImpl
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
class SecurityOutboxDispatcher(
    private val outboxRepository: SecurityOutboxRepositoryImpl,
    @Channel("security-events-out") private val emitter: Emitter<Record<String, String>>
) {
    @Scheduled(
        every = "5s",
        delayed = "5s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    fun dispatchScheduledBatch(): Uni<Void> =
        outboxRepository.listProcessableUni(BATCH_SIZE)
            .onItem().transformToMulti { Multi.createFrom().iterable(it) }
            .onItem().transformToUniAndConcatenate { event ->
                // `completionStage`, NOT `item`. `Emitter.send` returns a CompletionStage that
                // completes when the broker ACKS; wrapping it with `item { }` made the Uni's VALUE
                // that CompletionStage and completed the Uni the instant `send` RETURNED. So the
                // chain below ran unconditionally and marked the row SENT for a publish that had
                // not happened yet — and could still fail. A broker denial, an unreachable broker
                // or a serialisation error all lost the event silently, with the outbox reporting
                // success. `onFailure` could only ever see a synchronous throw from `send` itself,
                // which is the one case that does not happen.
                //
                // Measured while fixing #3393: security-scanner is refused by the broker as
                // ANONYMOUS on every publish, and its outbox would have recorded every one of
                // those as SENT. Verify a publish on the BROKER, never on outbox status.
                Uni.createFrom().completionStage {
                    emitter.send(
                        Record.of(Ids.randomId().toString(), event.payload),
                    )
                }
                    .chain { _ -> outboxRepository.markSentUni(event.eventId) }
                    .onFailure().recoverWithUni { ex ->
                        outboxRepository.markFailedUni(event.eventId, ex.message ?: ex.javaClass.simpleName)
                    }
            }
            .collect().asList()
            .replaceWithVoid()

    companion object {
        private const val BATCH_SIZE = 25
    }
}
