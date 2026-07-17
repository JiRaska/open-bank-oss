// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.integration

import com.openbank.notification.application.OperatorMessageRejected
import com.openbank.notification.application.OperatorMessageRequest
import com.openbank.notification.application.OperatorMessageService
import com.openbank.notification.domain.model.OperatorMessageTemplate
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import com.openbank.notification.it.PostgresTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.mailer.MockMailbox
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [OperatorMessageService] end to end against a real Postgres — the closed schema rejects
 * before anything is written, and an accepted request is persisted and actually mailed. No
 * Redis/ApprovalStore involvement: `compose()` never touches four-eyes, only the
 * `@Authorize`-annotated resource method does — see [ApprovalStoreWiringIT] for that half.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class OperatorMessageServiceIT {

    @Inject
    lateinit var service: OperatorMessageService

    @Inject
    lateinit var mailbox: MockMailbox

    @Inject
    lateinit var repository: NotificationRepository

    // service.compose is `suspend` and internally uses Panache.withTransaction, which needs a
    // Vert.x DUPLICATED context — a plain runBlocking on the JUnit thread does not have one
    // (see NotificationConsumer.consume's KDoc for the long version of why). subscribeAndAwait's
    // supplier runs ON that context; Dispatchers.Unconfined runs the coroutine body immediately
    // on the calling thread up to its first suspension point rather than hopping to a different
    // dispatcher's thread pool (GlobalScope.future's default Dispatchers.Default loses the
    // context that way — confirmed by making this test fail first). Panache's very first call
    // (SessionOperations.vertxContext()) reads Vertx.currentContext() synchronously before any
    // suspension, so Unconfined is what keeps it on the right thread.
    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    private fun bodyFor(partyId: UUID): String? = onVertxContext {
        Panache.withSession { repository.find("partyId", partyId).firstResult() }.awaitSuspending()
    }?.body

    @Test
    fun `an accepted request is persisted and actually delivered`() {
        val partyId = UUID.randomUUID()
        mailbox.clear()

        val notificationId = onVertxContext {
            service.compose(
                OperatorMessageRequest(
                    partyId = partyId,
                    template = OperatorMessageTemplate.SUPPORT_FOLLOWUP,
                    recipient = "followup@example.com",
                    variables = mapOf("ticketReference" to "TCK-42"),
                ),
            )
        }

        assertThat(notificationId).isNotNull()

        val sent = mailbox.getMailMessagesSentTo("followup@example.com")
        assertThat(sent).hasSize(1)
        assertThat(sent.first().html).contains("TCK-42")

        assertThat(bodyFor(partyId)).contains("TCK-42")
    }

    @Test
    fun `an undeclared variable is rejected before anything is persisted or sent`() {
        val partyId = UUID.randomUUID()
        mailbox.clear()

        assertThatThrownBy {
            onVertxContext {
                service.compose(
                    OperatorMessageRequest(
                        partyId = partyId,
                        template = OperatorMessageTemplate.SUPPORT_FOLLOWUP,
                        recipient = "rejected@example.com",
                        variables = mapOf("ticketReference" to "TCK-1", "internalNote" to "smuggled"),
                    ),
                )
            }
        }.isInstanceOf(OperatorMessageRejected::class.java)

        assertThat(mailbox.getMailMessagesSentTo("rejected@example.com")).isEmpty()
        assertThat(bodyFor(partyId)).isNull()
    }
}
