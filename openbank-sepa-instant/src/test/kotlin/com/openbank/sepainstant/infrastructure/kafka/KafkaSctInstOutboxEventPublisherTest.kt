// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.kafka

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KafkaSctInstOutboxEventPublisherTest {

    private val emitter = mockk<MutinyEmitter<String>>()
    private val publisher = KafkaSctInstOutboxEventPublisher(emitter)

    @Test
    fun `publish sends the raw outbox payload to the channel`(): Unit = runBlocking {
        every { emitter.send("""{"type":"SctInstPaymentSettled"}""") } returns Uni.createFrom().voidItem()

        publisher.publish("""{"type":"SctInstPaymentSettled"}""")

        verify(exactly = 1) { emitter.send("""{"type":"SctInstPaymentSettled"}""") }
    }

    @Test
    fun `publish propagates a transport failure so the outbox row stays undrained`(): Unit = runBlocking {
        every { emitter.send(any<String>()) } returns Uni.createFrom().failure(RuntimeException("broker down"))

        assertThatThrownBy { runBlocking { publisher.publish("payload") } }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("broker down")
    }
}
