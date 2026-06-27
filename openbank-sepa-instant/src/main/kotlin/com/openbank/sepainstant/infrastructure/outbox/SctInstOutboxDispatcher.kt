// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.outbox

import com.openbank.sepainstant.infrastructure.persistence.repository.SctInstOutboxRepositoryImpl
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel

@ApplicationScoped
class SctInstOutboxDispatcher(
    private val outboxRepository: SctInstOutboxRepositoryImpl,
    @Channel("sct-inst-events-out") private val emitter: MutinyEmitter<String>
) {
    @Scheduled(
        every = "5s",
        delayed = "5s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    fun dispatchScheduledBatch(): Uni<Void> =
        Panache.withSession {
            outboxRepository.listProcessableUni(BATCH_SIZE)
                .onItem().transformToMulti { Multi.createFrom().iterable(it) }
                .onItem().transformToUniAndConcatenate { event ->
                    emitter.send(event.payload)
                        .chain { _ -> outboxRepository.markSentUni(event.eventId) }
                        .onFailure().recoverWithUni { ex ->
                            outboxRepository.markFailedUni(event.eventId, ex.message ?: ex.javaClass.simpleName)
                        }
                }
                .collect().asList()
                .replaceWithVoid()
        }

    companion object {
        private const val BATCH_SIZE = 25
    }
}
