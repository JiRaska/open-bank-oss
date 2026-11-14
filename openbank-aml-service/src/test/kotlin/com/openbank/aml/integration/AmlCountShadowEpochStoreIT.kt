// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.integration

import com.openbank.aml.infrastructure.persistence.repository.AmlCountShadowEpochStore
import com.openbank.aml.it.PostgresRedisTestResource
import com.openbank.libs.fincrime.pack.CountShadowAppendOutcome
import com.openbank.libs.fincrime.pack.CountShadowEpoch
import com.openbank.libs.fincrime.pack.CountShadowReplayEvidence
import com.openbank.libs.fincrime.pack.PackDomain
import com.openbank.libs.fincrime.pack.PackRef
import com.openbank.libs.fincrime.pack.RecordedCountShadowEpoch
import com.openbank.libs.fincrime.pack.SignedCountShadowEpoch
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import jakarta.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
class AmlCountShadowEpochStoreIT {
    @Inject
    lateinit var epochs: AmlCountShadowEpochStore

    @Inject
    lateinit var pool: PgPool

    private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().private
    private val at = Instant.parse("2026-08-01T12:00:00Z")
    private val maker = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val baseline = PackRef("fixture-aml", 1, "a".repeat(64))
    private val candidate = PackRef("fixture-aml", 2, "b".repeat(64))

    @Test
    fun `unique immutable AML epochs preserve exact retry and effective rollback`(): Unit = runBlocking {
        val first = signedRecord(1, baseline, candidate)
        val rival = signedRecord(1, baseline, candidate)
        val rollback = signedRecord(2, candidate, baseline)
        assertThat(epochs.append(rollback)).isEqualTo(CountShadowAppendOutcome.CONFLICT)

        val attempts = listOf(first, rival).map { row -> async { epochs.append(row) } }.awaitAll()
        assertThat(attempts).containsExactlyInAnyOrder(
            CountShadowAppendOutcome.APPENDED,
            CountShadowAppendOutcome.CONFLICT,
        )
        val winner = requireNotNull(epochs.latest(PackDomain.AML))
        assertThat(epochs.append(winner)).isEqualTo(CountShadowAppendOutcome.REPLAYED)
        assertThat(epochs.append(rollback)).isEqualTo(CountShadowAppendOutcome.APPENDED)

        assertThat(epochs.effectiveAt(PackDomain.AML, at.minusNanos(1))).isNull()
        assertThat(epochs.effectiveAt(PackDomain.AML, at)).isEqualTo(winner)
        assertThat(epochs.effectiveAt(PackDomain.AML, at.plusSeconds(1))).isEqualTo(rollback)
        assertThat(epochs.recorded(PackDomain.AML, 1)).isEqualTo(winner)
        val mutation = runCatching {
            pool.query("UPDATE aml_count_shadow_epoch SET envelope_json = '{}' WHERE epoch_number = 1")
                .execute().awaitSuspending()
        }
        assertThat(mutation.exceptionOrNull()).hasMessageContaining("AML shadow epoch evidence is immutable")
    }

    private fun signedRecord(number: Long, from: PackRef, to: PackRef): RecordedCountShadowEpoch {
        val epoch = CountShadowEpoch(
            number = number,
            effectiveAt = at.plusSeconds(number - 1),
            signerKeyId = "synthetic-aml-publisher",
            proposalId = UUID.randomUUID(),
            proposedBy = maker,
            proposedAt = at.minusSeconds(30),
            approvedBy = reviewer,
            approvedAt = at.minusSeconds(15),
            replay = CountShadowReplayEvidence(from, to, "c".repeat(64), 2, 1),
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(key)
            update(epoch.signingInput())
            Base64.getEncoder().encodeToString(sign())
        }
        return RecordedCountShadowEpoch(
            PackDomain.AML,
            (number - 1).takeIf { it > 0 },
            SignedCountShadowEpoch(epoch, signature),
            at.minusSeconds(2),
        )
    }
}
