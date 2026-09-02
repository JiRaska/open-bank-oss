// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.SanctionsScreeningPort
import com.openbank.fx.application.port.out.ScreeningUnavailableException
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Resilient, fail-closed adapter over [SanctionsServiceClient] (ADR-0032 §C/§D). Transport faults are
 * retried and circuit-broken like the other inter-service adapters; once they are exhausted (or the
 * breaker is open) the raw failure is mapped to [ScreeningUnavailableException] so the use-case holds
 * the conversion rather than settling it un-screened. A sanctions *hit* is a normal 2xx response, not a
 * failure, and flows through as a [ScreeningResult].
 */
@ApplicationScoped
class SanctionsScreeningAdapter(@RestClient private val client: SanctionsServiceClient) : SanctionsScreeningPort {

    @Inject
    lateinit var self: SanctionsScreeningAdapter

    override suspend fun screen(name: String, role: ScreeningRole, idempotencyKey: String): ScreeningResult = try {
        self.screenWithResilience(name, role, idempotencyKey)
    } catch (ex: Exception) {
        throw ScreeningUnavailableException(ex)
    }

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(5_000)
    open suspend fun screenWithResilience(name: String, role: ScreeningRole, idempotencyKey: String): ScreeningResult {
        val response = client.screen(
            ScreenRequest(idempotencyKey = idempotencyKey, entityType = ENTITY_TYPE, name = name),
        ).awaitSuspending()
        return ScreeningResult(
            subject = name,
            role = role,
            status = mapStatus(response.status),
            score = response.overallScore ?: 0.0,
            matchedEntity = response.matches.firstOrNull()?.matchedName,
        )
    }

    /** Unknown / null statuses are treated as suspicious (ESCALATED), never silently CLEAR. */
    private fun mapStatus(remote: String?): ScreeningMatchStatus = when (remote?.uppercase()) {
        "CLEAR" -> ScreeningMatchStatus.CLEAR
        "POTENTIAL_HIT" -> ScreeningMatchStatus.POTENTIAL_HIT
        "HIT" -> ScreeningMatchStatus.HIT
        "WHITELISTED" -> ScreeningMatchStatus.WHITELISTED
        else -> ScreeningMatchStatus.ESCALATED
    }

    private companion object {
        // Sanctions matching is name-based; entityType is informational. See ADR-0032 scope note.
        const val ENTITY_TYPE = "INDIVIDUAL"
    }
}
