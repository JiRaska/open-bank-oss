// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.outbox

import com.openbank.security.infrastructure.persistence.repository.SecurityOutboxRepositoryImpl
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.util.UUID

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
                Uni.createFrom().item { emitter.send(Record.of(UUID.randomUUID().toString(), event.payload)) }
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
