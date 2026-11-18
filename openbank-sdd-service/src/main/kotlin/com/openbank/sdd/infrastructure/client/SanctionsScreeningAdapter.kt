// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.client

import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.sdd.application.port.out.SanctionsScreeningPort
import com.openbank.sdd.application.port.out.ScreeningUnavailableException
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Resilient, fail-closed adapter over [SanctionsServiceClient] (ADR-0032 §C/§D). Once retries are
 * exhausted or the breaker is open, the failure becomes [ScreeningUnavailableException] so the gate
 * holds the collection. A hit is a normal 2xx response and flows through as a [ScreeningResult].
 */
@ApplicationScoped
class SanctionsScreeningAdapter(@RestClient private val client: SanctionsServiceClient) : SanctionsScreeningPort {

    @Inject
    lateinit var self: SanctionsScreeningAdapter

    override fun screen(name: String, role: ScreeningRole, idempotencyKey: String): Uni<ScreeningResult> =
        Uni.createFrom().deferred { self.screenWithResilience(name, role, idempotencyKey) }
            .onFailure().transform { ScreeningUnavailableException(it) }

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(CALL_TIMEOUT_MS)
    fun screenWithResilience(name: String, role: ScreeningRole, idempotencyKey: String): Uni<ScreeningResult> =
        client.screen(ScreenRequest(idempotencyKey = idempotencyKey, entityType = ENTITY_TYPE, name = name))
            .map { response ->
                ScreeningResult(
                    subject = name,
                    role = role,
                    // Unknown / null statuses are ESCALATED, never CLEAR (ADR-0353 D5).
                    status = ScreeningMatchStatus.fromWire(response.status?.takeIf { it == it.trim() }),
                    score = response.overallScore ?: 0.0,
                    matchedEntity = response.matches.firstOrNull()?.matchedName,
                )
            }

    private companion object {
        // Sanctions matching is name-based; entityType is informational (ADR-0032 scope note).
        const val ENTITY_TYPE = "INDIVIDUAL"
    }
}

/** Upper bound on one call to the compliance service, retries included per attempt. */
private const val CALL_TIMEOUT_MS = 5_000L
