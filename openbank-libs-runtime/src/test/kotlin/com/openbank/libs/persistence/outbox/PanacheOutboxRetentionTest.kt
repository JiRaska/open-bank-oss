// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier

/** Verifies retention wiring and query bindings; database execution is covered by service ITs. */
class PanacheOutboxRetentionTest {

    private val session = mockk<Mutiny.Session>()
    private val update = mockk<Mutiny.Query<Int>>()
    private val parameters = mutableMapOf<String, Any?>()

    @BeforeEach
    fun transactionBoundary() {
        mockkStatic(Panache::class)
        every { Panache.withTransaction(any<Supplier<Uni<Any>>>()) } answers {
            firstArg<Supplier<Uni<Any>>>().get()
        }
        every { Panache.getSession() } returns Uni.createFrom().item(session)
        every { update.setParameter(any<String>(), any()) } answers {
            parameters[firstArg()] = secondArg()
            update
        }
        every { update.executeUpdate() } returns Uni.createFrom().item(3)
    }

    @AfterEach
    fun restoreBoundary() = unmockkStatic(Panache::class)

    @Test
    fun `retention delegate executes sent deletion with caller cutoff and bounded limit`(): Unit = runBlocking {
        val shape = OutboxTableShape("test_outbox", sentAtColumn = "published_at")
        val target: SentOutboxRetention = PanacheOutboxRetention(shape)
        val now = Instant.parse("2026-10-23T03:17:00Z")
        every { session.createNativeQuery<Int>(OutboxSql.purgeSent(shape)) } returns update

        assertThat(target.sentRetentionExempt).isFalse()
        assertThat(target.purgeSent(Duration.ofDays(1), 17, now)).isEqualTo(3)
        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(
            mapOf("sent" to "SENT", "cut" to now.minus(Duration.ofDays(1)), "limit" to 17),
        )
        verify(exactly = 1) { update.executeUpdate() }
    }

    @Test
    fun `the retention label is the table name minus its outbox suffix, dasherised`() {
        assertThat(PanacheOutboxRetention(OutboxTableShape("sca_outbox")).retentionLabel)
            .isEqualTo("sca")
        assertThat(PanacheOutboxRetention(OutboxTableShape("sepa_payment_outbox")).retentionLabel)
            .isEqualTo("sepa-payment")
    }

    @Test
    fun `a plain retention delegate is not exempt from the enforced retention gate`() {
        // The interface default is the behaviour under test: only a reasoned, per-service override
        // may claim an exemption.
        assertThat(PanacheOutboxRetention(OutboxTableShape("sca_outbox")).sentRetentionExempt).isFalse()
    }
}
