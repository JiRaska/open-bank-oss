// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import com.openbank.fraud.infrastructure.persistence.FraudCountShadowEpochStore
import com.openbank.fraud.it.PostgresRedisTestResource
import com.openbank.libs.fincrime.pack.CountShadowAppendOutcome
import com.openbank.libs.fincrime.pack.CountShadowEpoch
import com.openbank.libs.fincrime.pack.CountShadowReplayEvidence
import com.openbank.libs.fincrime.pack.PackDomain
import com.openbank.libs.fincrime.pack.PackRef
import com.openbank.libs.fincrime.pack.RecordedCountShadowEpoch
import com.openbank.libs.fincrime.pack.SignedCountShadowEpoch
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
class FraudCountShadowEpochStoreIT {
    @Inject
    lateinit var epochs: FraudCountShadowEpochStore

    @Inject
    lateinit var vertx: Vertx

    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val start = Instant.parse("2026-08-01T12:00:00Z")
    private val maker = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val baseline = PackRef("fixture-fraud", 1, "a".repeat(64))
    private val candidate = PackRef("fixture-fraud", 2, "b".repeat(64))

    @Test
    fun `a locked database head accepts one proposal and preserves replay and rollback history`() {
        val first = record(1, null, baseline, candidate)
        val rival = record(1, null, baseline, candidate)
        val attempts = listOf(first, rival).map { row -> onVertx { epochs.append(row) } }
        val outcomes = attempts.map { it.get(45, TimeUnit.SECONDS) }
        assertThat(
            outcomes,
        ).containsExactlyInAnyOrder(CountShadowAppendOutcome.APPENDED, CountShadowAppendOutcome.CONFLICT)
        val winner = if (outcomes.first() == CountShadowAppendOutcome.APPENDED) first else rival
        val current = onVertx { epochs.latest(PackDomain.FRAUD) }.get(20, TimeUnit.SECONDS)
        assertThat(current).isEqualTo(winner)
        assertThat(onVertx { epochs.append(winner) }.get(20, TimeUnit.SECONDS))
            .isEqualTo(CountShadowAppendOutcome.REPLAYED)

        val rollback = record(2, 1, candidate, baseline)
        assertThat(onVertx { epochs.append(rollback) }.get(20, TimeUnit.SECONDS))
            .isEqualTo(CountShadowAppendOutcome.APPENDED)
        assertThat(onVertx { epochs.effectiveAt(PackDomain.FRAUD, start.minusNanos(1)) }.get(20, TimeUnit.SECONDS))
            .isNull()
        assertThat(onVertx { epochs.effectiveAt(PackDomain.FRAUD, start) }.get(20, TimeUnit.SECONDS))
            .isEqualTo(winner)
        assertThat(onVertx { epochs.effectiveAt(PackDomain.FRAUD, start.plusSeconds(1)) }.get(20, TimeUnit.SECONDS))
            .isEqualTo(rollback)
        assertThat(onVertx { epochs.recorded(PackDomain.FRAUD, 1) }.get(20, TimeUnit.SECONDS))
            .isEqualTo(winner)
        assertThatThrownBy(::modifyEpoch).isInstanceOf(SQLException::class.java)
    }

    private fun record(number: Long, previous: Long?, from: PackRef, to: PackRef): RecordedCountShadowEpoch {
        val epoch = CountShadowEpoch(
            number = number,
            effectiveAt = start.plusSeconds(number - 1),
            signerKeyId = "synthetic-fraud-publisher",
            proposalId = UUID.randomUUID(),
            proposedBy = maker,
            proposedAt = start.minusSeconds(30),
            approvedBy = reviewer,
            approvedAt = start.minusSeconds(15),
            replay = CountShadowReplayEvidence(from, to, "c".repeat(64), 2, 1),
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(epoch.signingInput())
            sign()
        }
        val envelope = SignedCountShadowEpoch(epoch, Base64.getEncoder().encodeToString(signature))
        return RecordedCountShadowEpoch(PackDomain.FRAUD, previous, envelope, start.minusSeconds(2))
    }

    private fun modifyEpoch() {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.createStatement().use {
                it.executeUpdate("UPDATE fraud_count_shadow_epoch SET envelope_json = '{}' WHERE epoch_number = 1")
            }
        }
    }

    private fun <T> onVertx(block: suspend () -> T): CompletableFuture<T> {
        val completion = CompletableFuture<T>()
        val context = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(context, true)
        val dispatcher = Executor { task -> context.runOnContext { task.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            runCatching {
                block()
            }.onSuccess { completion.complete(it) }.onFailure { completion.completeExceptionally(it) }
        }
        return completion
    }
}
