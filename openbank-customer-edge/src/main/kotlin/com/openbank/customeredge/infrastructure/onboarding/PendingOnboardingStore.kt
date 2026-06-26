// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.customeredge.infrastructure.onboarding

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import jakarta.enterprise.context.ApplicationScoped

/**
 * An onboarding paused on a pid four-eyes identity-verification case (ADR-0072). Captured when
 * /resolve returns NEEDS_MANUAL_VERIFICATION, replayed when the case is DECIDED.
 *
 * Privacy: the plaintext RČ (taxId) is deliberately NOT stored — it never leaves pid. Probabilistic
 * and namesake cases (the bulk of manual verifications) carry no RČ anyway; an RČ-collision case
 * resumes by linking to the already-known party, so the RČ is not needed to complete it.
 */
data class PendingOnboarding(
    val caseId: String,
    val callerPartyId: String,
    val legalName: String,
    val email: String,
    val dateOfBirth: String? = null,
    val nationality: String? = null,
    val phone: String? = null,
)

/**
 * Redis-backed store for pending onboardings, keyed by caseId with a TTL. Uses the blocking
 * [RedisDataSource] (no coroutines) so it can be called directly from the @Blocking REST gate and
 * the @Blocking Kafka consumer.
 */
@ApplicationScoped
class PendingOnboardingStore(redis: RedisDataSource, private val objectMapper: ObjectMapper) {
    private val values = redis.value(String::class.java)

    fun save(pending: PendingOnboarding) {
        values.set(key(pending.caseId), objectMapper.writeValueAsString(pending), SetArgs().ex(TTL_SECONDS))
        Log.debugf("PendingOnboardingStore: saved case=%s ttl=%ds", pending.caseId, TTL_SECONDS)
    }

    fun find(caseId: String): PendingOnboarding? {
        val json = values.get(key(caseId)) ?: return null
        return runCatching { objectMapper.readValue(json, PendingOnboarding::class.java) }.getOrElse { e ->
            Log.warnf(e, "PendingOnboardingStore: failed to deserialise case=%s", caseId)
            null
        }
    }

    fun delete(caseId: String) {
        values.getdel(key(caseId))
    }

    private fun key(caseId: String) = "edge:pending-onboarding:$caseId"

    companion object {
        // 30 days — long enough for an operator to adjudicate; bounded so stale onboardings expire.
        const val TTL_SECONDS = 30L * 24 * 3600
    }
}
